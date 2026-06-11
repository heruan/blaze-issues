package to.lova.blaze.issues.updatable_set_element_collection_npe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.blazebit.persistence.Criteria;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.spi.CriteriaBuilderConfiguration;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViews;
import com.blazebit.persistence.view.spi.EntityViewConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceConfiguration;
import java.util.HashSet;
import java.util.Set;
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
 * Reproducer for a Blaze 1.6.18 entity-view flush NPE:
 * saving an {@code @UpdatableEntityView} (default
 * {@code FlushStrategy.QUERY}) whose entity declares an
 * {@code @ElementCollection} of a basic type as a {@code Set} crashes
 * with
 * {@code NullPointerException: Cannot invoke
 * "ViewToEntityMapper.getViewIdAccessor()" because the return value of
 * "TypeDescriptor.getLoadOnlyViewToEntityMapper()" is null} at
 * {@code CollectionAttributeFlusher.getAddedAndRemovedElementsForInverseFlusher:1975}.
 *
 * <p>Root cause: {@code TypeDescriptor.forType} computes
 * {@code identifiable = jpaEntity || !jpaManaged}, which is TRUE for
 * basic element types (String, enums) even though their
 * {@code viewToEntityMapper} / {@code loadOnlyViewToEntityMapper} are
 * null. {@code getAddedAndRemovedElementsForInverseFlusher}
 * dereferences {@code getLoadOnlyViewToEntityMapper().getViewIdAccessor()}
 * whenever {@code elementDescriptor.isIdentifiable()} — guaranteed NPE.
 *
 * <p>All four conditions are required:
 * <ol>
 *   <li>{@code @UpdatableEntityView} with the default
 *       {@code FlushStrategy.QUERY} — see
 *       {@link #entityFlushStrategySucceeds()} for the ENTITY-strategy
 *       counterproof;</li>
 *   <li>the {@code @ElementCollection} of a basic type is a
 *       {@code Set} — a {@code List} (bag) exits
 *       {@code getFusedOperations} early via
 *       {@code collectionInstantiator.allowsDuplicates()}, which is
 *       why Blaze's own
 *       {@code AbstractEntityViewUpdateBasicCollectionsTest}
 *       ({@code List<String>}) never hits it;</li>
 *   <li>the update produces add/remove diff actions (here: setter
 *       called with a set containing one extra element);</li>
 *   <li>the owning entity is NOT in the persistence context when
 *       {@code evm.save} runs (view loaded with one
 *       {@code EntityManager}, saved with a fresh one) — a managed
 *       entity routes the flush down the entity path instead.</li>
 * </ol>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReproTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    EntityManagerFactory emf;
    CriteriaBuilderFactory cbf;
    EntityViewManager evm;

    Long queryUserId;
    Long entityUserId;

    @BeforeAll
    void setUp() {
        emf = new HibernatePersistenceConfiguration("repro")
                .managedClass(UserAccount.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                .property(JdbcSettings.JAKARTA_JDBC_DRIVER, "org.postgresql.Driver")
                .property(SchemaToolingSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, "create-drop")
                .createEntityManagerFactory();

        CriteriaBuilderConfiguration cbConfig = Criteria.getDefault();
        cbf = cbConfig.createCriteriaBuilderFactory(emf);

        EntityViewConfiguration evConfig = EntityViews.createDefaultConfiguration();
        evConfig.addEntityView(UserAccountUpdateView.class);
        evConfig.addEntityView(UserAccountEntityFlushView.class);
        evm = evConfig.createEntityViewManager(cbf);

        queryUserId = seedUser("query-strategy-user");
        entityUserId = seedUser("entity-strategy-user");
    }

    @AfterAll
    void tearDown() {
        if (emf != null) emf.close();
    }

    private Long seedUser(String name) {
        EntityManager em = emf.createEntityManager();
        try {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            UserAccount user = new UserAccount();
            user.name = name;
            user.roles.add("ADMIN");
            em.persist(user);
            tx.commit();
            return user.id;
        } finally {
            em.close();
        }
    }

    /** Loads the view with a dedicated, immediately-closed EntityManager (condition 4). */
    private <T> T find(Class<T> viewType, Long id) {
        EntityManager em = emf.createEntityManager();
        try {
            return evm.find(em, viewType, id);
        } finally {
            em.close();
        }
    }

    /**
     * Bug: default QUERY flush strategy. Load the view, replace the
     * roles set with a superset (one added element — an add/remove
     * diff, not a clear-first replace), save with a fresh
     * EntityManager. Expected: the row's element collection gains the
     * new element. On 1.6.18 {@code evm.save} throws the
     * {@code getLoadOnlyViewToEntityMapper()} NPE.
     */
    @Test
    @DisplayName("QUERY flush of Set<String> @ElementCollection diff — NPE")
    void queryFlushStrategyCrashes() {
        UserAccountUpdateView view = find(UserAccountUpdateView.class, queryUserId);

        EntityManager em = emf.createEntityManager();
        try {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            Set<String> roles = new HashSet<>(view.getRoles());
            roles.add("TREASURER");
            view.setRoles(roles);

            assertThatCode(() -> {
                        evm.save(em, view);
                        tx.commit();
                    })
                    .doesNotThrowAnyException();
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }

        assertThat(find(UserAccountUpdateView.class, queryUserId).getRoles())
                .containsExactlyInAnyOrder("ADMIN", "TREASURER");
    }

    /**
     * Counterproof: the exact same flow through a view declared with
     * {@code FlushStrategy.ENTITY} succeeds, scoping the bug to the
     * QUERY flush codepath
     * ({@code CollectionAttributeFlusher.getFusedOperations}).
     */
    @Test
    @DisplayName("ENTITY flush of the same Set<String> diff — succeeds")
    void entityFlushStrategySucceeds() {
        UserAccountEntityFlushView view = find(UserAccountEntityFlushView.class, entityUserId);

        EntityManager em = emf.createEntityManager();
        try {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            Set<String> roles = new HashSet<>(view.getRoles());
            roles.add("TREASURER");
            view.setRoles(roles);
            evm.save(em, view);
            tx.commit();
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }

        assertThat(find(UserAccountEntityFlushView.class, entityUserId).getRoles())
                .containsExactlyInAnyOrder("ADMIN", "TREASURER");
    }
}
