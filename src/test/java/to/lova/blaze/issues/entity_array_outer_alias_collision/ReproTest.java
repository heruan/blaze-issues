package to.lova.blaze.issues.entity_array_outer_alias_collision;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazebit.persistence.Criteria;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.spi.CriteriaBuilderConfiguration;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.blazebit.persistence.view.EntityViews;
import com.blazebit.persistence.view.spi.EntityViewConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * Reproducer for a Blaze 1.6.20 entity-view bug: the entity-array
 * expression {@code @Mapping("UserRole[user.id = VIEW(id)].role")}
 * resolves the first path segment of the bracket predicate against
 * the DEFAULT ALIAS OF THE OUTER ROOT when the navigated field name
 * coincides with that alias ({@code camelCase(SimpleName)} of the
 * root entity — here {@code user} for root {@link User}).
 *
 * <p>Per the Blaze core docs, inside the brackets "the implicit root
 * for path expressions is the joined entity itself": {@code user.id}
 * should resolve to {@code UserRole.user.id}. Instead the token
 * {@code user} wins resolution against the identically-named outer
 * alias, the generated ON clause degenerates into the tautology
 * {@code u1_0.id = u1_0.id} (the joined UserRole's alias never
 * appears in it), and every row silently receives the union of all
 * other rows' data. No exception — just wrong data.
 *
 * <p>{@link #correlatedMappingReturnsIsolatedRoles()} is the control:
 * the same join expressed with {@code @MappingCorrelatedSimple}
 * (identical {@code user.id} token in the correlation expression)
 * returns correct isolated roles, proving the bracket-sugar code path
 * is broken, not the model.
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
                .managedClass(User.class)
                .managedClass(UserRole.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                .property(JdbcSettings.JAKARTA_JDBC_DRIVER, "org.postgresql.Driver")
                .property(JdbcSettings.SHOW_SQL, "true")
                .property(SchemaToolingSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, "create-drop")
                .createEntityManagerFactory();

        CriteriaBuilderConfiguration cbConfig = Criteria.getDefault();
        cbf = cbConfig.createCriteriaBuilderFactory(emf);

        EntityViewConfiguration evConfig = EntityViews.createDefaultConfiguration();
        evConfig.addEntityView(UserView.class);
        evConfig.addEntityView(UserCorrelatedView.class);
        evm = evConfig.createEntityViewManager(cbf);

        seed("alice", "ADMIN");
        seed("bob", "SECRETARY");
    }

    @AfterAll
    void tearDown() {
        if (emf != null) emf.close();
    }

    private void seed(String name, String role) {
        EntityManager em = emf.createEntityManager();
        try {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            User user = new User();
            user.name = name;
            em.persist(user);
            UserRole userRole = new UserRole();
            userRole.user = user;
            userRole.role = role;
            em.persist(userRole);
            tx.commit();
        } finally {
            em.close();
        }
    }

    private <V> Map<String, Set<String>> rolesByName(Class<V> viewType,
            Function<V, String> name, Function<V, Set<String>> roles) {
        EntityManager em = emf.createEntityManager();
        try {
            List<V> views = evm.applySetting(EntityViewSetting.create(viewType), cbf.create(em, User.class))
                    .getResultList();
            return views.stream().collect(Collectors.toMap(name, roles));
        } finally {
            em.close();
        }
    }

    /**
     * Bug: the entity-array predicate {@code UserRole[user.id = VIEW(id)]}
     * joins on the tautology {@code u1_0.id = u1_0.id}, so alice and
     * bob each get {@code {ADMIN, SECRETARY}} — the union of all
     * rows' roles — instead of their own single role.
     */
    @Test
    @DisplayName("entity-array predicate UserRole[user.id = VIEW(id)] — roles leak across users")
    void entityArrayMappingReturnsIsolatedRoles() {
        Map<String, Set<String>> roles =
                rolesByName(UserView.class, UserView::getName, UserView::getRoles);

        assertThat(roles.get("alice")).containsExactlyInAnyOrder("ADMIN");
        assertThat(roles.get("bob")).containsExactlyInAnyOrder("SECRETARY");
    }

    /**
     * Control: the same join expressed with an explicit correlated
     * mapping returns correct isolated roles despite the identical
     * {@code user} field-name/outer-alias coincidence.
     */
    @Test
    @DisplayName("correlated mapping with user.id = correlationKey — roles stay isolated")
    void correlatedMappingReturnsIsolatedRoles() {
        Map<String, Set<String>> roles = rolesByName(
                UserCorrelatedView.class, UserCorrelatedView::getName, UserCorrelatedView::getRoles);

        assertThat(roles.get("alice")).containsExactlyInAnyOrder("ADMIN");
        assertThat(roles.get("bob")).containsExactlyInAnyOrder("SECRETARY");
    }
}
