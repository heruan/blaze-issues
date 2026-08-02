package to.lova.blaze.issues.bounded_count_query_parameter_binding;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazebit.persistence.Criteria;
import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceConfiguration;
import java.util.UUID;
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
 * Reproducer for a bounded count query bug first seen on Blaze 1.6.18:
 * {@code CriteriaBuilder.getQueryRootCountQuery(long maxValue)} — the
 * bounded count ("count at most N rows", typically used for existence
 * checks) — fails with a parameter binding error when the query has
 * bound parameters in its predicates. The bounded-count implementation
 * internally relies on the VALUES-clause emulation, and its synthetic
 * parameter is not correctly registered in the generated query.
 *
 * <p>The VALUES fixes that landed in 1.6.19 (issue
 * <a href="https://github.com/Blazebit/blaze-persistence/issues/2113">#2113</a>
 * and related) did NOT resolve it: on 1.6.20 every test below still
 * fails at query construction time with
 * {@code IllegalArgumentException: Parameter name "dual__value_0" does
 * not exist} at {@code ParameterManager.parameterizeQuery:223}.
 *
 * <p>Root cause on 1.6.20:
 * {@code CriteriaBuilderImpl.getQueryRootCountQuery(maximumCount)}
 * stores the bound in {@code cachedQueryRootMaximumCount}
 * ({@code getCountQueryRootQueryStringWithoutCheck}), but
 * {@code AbstractFullQueryBuilder.getCountQuery(String, boolean)}
 * decides between the plain-JPQL path and the dual-VALUES path by
 * checking the sibling field {@code cachedMaximumCount}, which is only
 * set by {@code getCountQuery(maximumCount)} and still holds
 * {@code Long.MAX_VALUE}. The bounded count query string therefore
 * contains the {@code dual_} VALUES emulation with its synthetic
 * {@code :dual__value_0} parameter, yet parameterization takes the
 * plain path ({@code parameterManager.parameterizeQuery(countQuery)})
 * where the {@code dual__value_0 -> dual_} values-parameter mapping was
 * never registered (it only went into local copies destined for the
 * dual-path {@code CustomSQLTypedQuery}), so the lookup throws.
 *
 * <p>These tests assert the CORRECT behavior, so they pass if and only
 * if the bug is fixed. A second test covers multiple-parameter
 * re-binding with two bound predicates.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReproTest {

    static final UUID CLIENT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID CLIENT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    static final UUID CLIENT_C = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    EntityManagerFactory emf;
    CriteriaBuilderFactory cbf;

    @BeforeAll
    void setUp() {
        emf = new HibernatePersistenceConfiguration("repro")
                .managedClass(Passage.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                .property(JdbcSettings.JAKARTA_JDBC_DRIVER, "org.postgresql.Driver")
                .property(SchemaToolingSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, "create-drop")
                .createEntityManagerFactory();

        cbf = Criteria.getDefault().createCriteriaBuilderFactory(emf);

        EntityManager em = emf.createEntityManager();
        try {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            em.persist(new Passage(1L, CLIENT_A, "first passage of client A"));
            em.persist(new Passage(2L, CLIENT_A, "second passage of client A"));
            em.persist(new Passage(3L, CLIENT_B, "only passage of client B"));
            tx.commit();
        } finally {
            em.close();
        }
    }

    @AfterAll
    void tearDown() {
        if (emf != null) {
            emf.close();
        }
    }

    /**
     * Bounded count with a single bound parameter. Two rows match
     * {@code clientId = A}, but the count is capped at 1; on 1.6.20
     * {@code getQueryRootCountQuery(1L)} itself throws the
     * {@code dual__value_0} binding error instead.
     */
    @Test
    @DisplayName("getQueryRootCountQuery(1) with one bound parameter — returns 1")
    void boundedCountWithOneBoundParameter() {
        EntityManager em = emf.createEntityManager();
        try {
            Long count = cbf.create(em, Passage.class)
                    .where("clientId").eq(CLIENT_A)
                    .getQueryRootCountQuery(1L)
                    .getSingleResult();
            assertThat(count).isEqualTo(1L);
        } finally {
            em.close();
        }
    }

    /**
     * Same bounded count against a clientId with no matching rows: the
     * query must execute (no binding error) and report 0.
     */
    @Test
    @DisplayName("getQueryRootCountQuery(1) with no matching rows — returns 0")
    void boundedCountWithNoMatchingRows() {
        EntityManager em = emf.createEntityManager();
        try {
            Long count = cbf.create(em, Passage.class)
                    .where("clientId").eq(CLIENT_C)
                    .getQueryRootCountQuery(1L)
                    .getSingleResult();
            assertThat(count).isEqualTo(0L);
        } finally {
            em.close();
        }
    }

    /**
     * Bounded count with TWO bound parameters ({@code clientId = A}
     * and a LIKE on {@code note}), covering re-binding of multiple
     * parameters into the generated bounded-count query. Only one of
     * the two client-A rows matches the LIKE pattern.
     */
    @Test
    @DisplayName("getQueryRootCountQuery(1) with two bound parameters — returns 1")
    void boundedCountWithTwoBoundParameters() {
        EntityManager em = emf.createEntityManager();
        try {
            Long count = cbf.create(em, Passage.class)
                    .where("clientId").eq(CLIENT_A)
                    .where("note").like().value("second%").noEscape()
                    .getQueryRootCountQuery(1L)
                    .getSingleResult();
            assertThat(count).isEqualTo(1L);
        } finally {
            em.close();
        }
    }
}
