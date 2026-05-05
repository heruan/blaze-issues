package to.lova.blaze.issues.paginated_count_self_subview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.blazebit.persistence.Criteria;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.spi.CriteriaBuilderConfiguration;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.blazebit.persistence.view.Sorters;
import com.blazebit.persistence.view.spi.EntityViewConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SchemaToolingSettings;
import org.hibernate.jpa.HibernatePersistenceConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reproducer for a Blaze 1.6.18 query-rendering bug:
 * {@link com.blazebit.persistence.PaginatedCriteriaBuilder#getCountQuery()}
 * fails with
 * {@code org.hibernate.query.SemanticException: Could not interpret path
 * expression 'reversesMovement_1'} when the projected
 * {@link com.blazebit.persistence.view.EntityView} declares a self-reference
 * subview AND the underlying CB filters on the same association.
 *
 * <p>Setup:
 * <ul>
 *   <li>{@link Movement} has a self-reference {@code reversesMovement: Movement}.</li>
 *   <li>{@link MovementView} exposes that as a subview using the
 *       {@link MovementReferenceView} projection (different view type for
 *       the same entity, to avoid the recursive subview cycle Blaze
 *       rejects at boot). Blaze auto-fetches this subview via a
 *       LEFT JOIN with a Hibernate-generated alias, typically
 *       {@code reversesMovement_1}.</li>
 *   <li>The CB applies a paginated entity-view setting plus either a
 *       {@code where("m.reversesMovement").isNull()} predicate or a
 *       correlated {@code whereNotExists().from(Movement, "rev")
 *       .where("rev.reversesMovement.id").eqExpression("m.id")} subquery.</li>
 * </ul>
 *
 * <p>The first query in the paginated pair (the COUNT query) is the
 * one that explodes. Walking the FK column directly
 * ({@code "m.reversesMovement.id"}) bypasses the bug — see
 * {@link #workaroundFkPathSucceeds()}. {@code OUTER(id)} does not
 * help in the related NOT EXISTS variant: Hibernate resolves the path
 * against the fetch alias before the OUTER substitution runs.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReproTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    EntityManagerFactory emf;
    CriteriaBuilderFactory cbf;
    EntityViewManager evm;

    @BeforeAll
    void setUp() {
        emf = new HibernatePersistenceConfiguration("repro")
                .managedClass(Movement.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                .property(JdbcSettings.JAKARTA_JDBC_DRIVER, "org.postgresql.Driver")
                .property(SchemaToolingSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, "create-drop")
                .createEntityManagerFactory();

        CriteriaBuilderConfiguration cbConfig = Criteria.getDefault();
        cbf = cbConfig.createCriteriaBuilderFactory(emf);

        EntityViewConfiguration evConfig = com.blazebit.persistence.view.EntityViews.createDefaultConfiguration();
        evConfig.addEntityView(MovementView.class);
        evConfig.addEntityView(MovementReferenceView.class);
        evm = evConfig.createEntityViewManager(cbf);

        seed();
    }

    @AfterAll
    void tearDown() {
        if (emf != null) emf.close();
    }

    private void seed() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        Movement m1 = new Movement();
        m1.date = LocalDate.of(2026, 2, 15);
        m1.amount = new BigDecimal("100");
        em.persist(m1);

        Movement m2 = new Movement();
        m2.date = LocalDate.of(2026, 4, 10);
        m2.amount = new BigDecimal("50");
        em.persist(m2);
        tx.commit();
        em.close();
    }

    /**
     * Bug: walking the self-reference association directly
     * ({@code m.reversesMovement IS NULL}) inside a paginated
     * entity-view setting. The COUNT query emitted by
     * {@link com.blazebit.persistence.PaginatedCriteriaBuilder#getCountQuery()}
     * crashes with
     * {@code Could not interpret path expression 'reversesMovement_1'}
     * because Hibernate has bound the path to the fetch-join alias of
     * {@link MovementView#getReversesMovement()} and the count emitter
     * cannot resolve it.
     *
     * <p>Expected: the call should run normally (returning two rows,
     * since both seeded movements have {@code reversesMovement} null).
     * On 1.6.18 + Hibernate 7.2.x it throws.
     */
    @Test
    @DisplayName("paginated count + WHERE m.<self-reference-association> IS NULL — crashes")
    void crashesOnDirectAssociationIsNull() {
        EntityManager em = emf.createEntityManager();
        try {
            EntityViewSetting<MovementView, ?> setting = EntityViewSetting.create(MovementView.class, 0, 50);
            setting.addAttributeSorter("id", Sorters.ascending());

            var cb = cbf.create(em, Movement.class, "m");
            cb.where("m.reversesMovement").isNull();

            assertThatCode(() -> evm.applySetting(setting, cb).getResultList())
                    .doesNotThrowAnyException();
        } finally {
            em.close();
        }
    }

    /**
     * Workaround: walk the FK column directly via {@code .id} instead
     * of the association. This bypasses the implicit-join codepath
     * that collapses onto the fetch alias.
     *
     * <p>Expected to pass on 1.6.18 already; included as a regression
     * guard so we know it keeps working once the upstream bug is fixed
     * and we revert to the idiomatic association form.
     */
    @Test
    @DisplayName("workaround — WHERE m.<self-reference-association>.id IS NULL — succeeds")
    void workaroundFkPathSucceeds() {
        EntityManager em = emf.createEntityManager();
        try {
            EntityViewSetting<MovementView, ?> setting = EntityViewSetting.create(MovementView.class, 0, 50);
            setting.addAttributeSorter("id", Sorters.ascending());

            var cb = cbf.create(em, Movement.class, "m");
            cb.where("m.reversesMovement.id").isNull();

            List<MovementView> rows = evm.applySetting(setting, cb).getResultList();
            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(v -> assertThat(v.getReversesMovement()).isNull());
        } finally {
            em.close();
        }
    }
}
