package to.lova.blaze.issues.fromvalues_leftjoin_cte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.blazebit.persistence.Criteria;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.spi.CriteriaBuilderConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.Tuple;
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
 * Reproducer for a Blaze 1.6.18 SQL-rendering bug:
 * {@code fromValues(...) + leftJoinOn(...)} INSIDE a {@code with(...)}
 * CTE definition crashes during SQL rendering with
 * {@code StringIndexOutOfBoundsException} at
 * {@code EntityFunction.removeSyntheticPredicate:110}.
 *
 * <p>The same shape used in the main query
 * ({@code cbf.create(em, X.class).fromValues(...).leftJoinOn(...)})
 * works correctly — see {@code mainQueryShapeWorks}, which mirrors
 * Blaze's own {@code InlineCTETest.testJoinInlineEntityWithLimit}.
 *
 * <p>Related upstream issues:
 * <ul>
 *   <li><a href="https://github.com/Blazebit/blaze-persistence/issues/2093">#2093</a>
 *       (open) — same {@code leftJoinOn(CTEEntity)} codepath, manifests
 *       as malformed SQL ({@code null is null and 999=999}) instead of
 *       SIOOBE.</li>
 *   <li><a href="https://github.com/Blazebit/blaze-persistence/issues/1975">#1975</a>
 *       (closed in 1.6.15) — recommends {@code with(CteType.class, false)}
 *       to disable inlining as a workaround. For the cumulative-balance
 *       shape used here it produces silently incorrect results
 *       (range predicate {@code agg.month <= ms} effectively true for
 *       all rows), so it is not adopted.</li>
 * </ul>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReproTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    EntityManagerFactory emf;
    CriteriaBuilderFactory cbf;

    @BeforeAll
    void setUp() {
        emf = new HibernatePersistenceConfiguration("repro")
                .managedClass(Movement.class)
                .managedClass(MonthlyAggCte.class)
                .managedClass(CashBalanceCte.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                .property(JdbcSettings.JAKARTA_JDBC_DRIVER, "org.postgresql.Driver")
                .property(SchemaToolingSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, "create-drop")
                .createEntityManagerFactory();

        CriteriaBuilderConfiguration config = Criteria.getDefault();
        cbf = config.createCriteriaBuilderFactory(emf);

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
     * Baseline: {@code fromValues + leftJoinOn} in the MAIN query
     * (no enclosing {@code with(...)}). Already covered by Blaze's
     * own {@code InlineCTETest}; included here to confirm the
     * setup is correct before triggering the bug.
     */
    @Test
    @DisplayName("fromValues + leftJoinOn in MAIN query (with inner CTE) — works")
    void mainQueryShapeWorks() {
        EntityManager em = emf.createEntityManager();
        try {
            List<LocalDate> months = List.of(
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 2, 1),
                    LocalDate.of(2026, 3, 1),
                    LocalDate.of(2026, 4, 1));

            var cb = cbf.create(em, Tuple.class);
            cb.with(MonthlyAggCte.class)
                    .from(Movement.class, "m")
                    .groupBy("FUNCTION('TRUNC_MONTH', m.date)")
                    .bind("month")
                    .select("FUNCTION('TRUNC_MONTH', m.date)")
                    .bind("amount")
                    .select("SUM(m.amount)")
                    .end();

            cb.fromValues(Movement.class, "date", "ms", months)
                    .leftJoinOn(MonthlyAggCte.class, "agg")
                    .on("agg.month")
                    .eqExpression("ms")
                    .end()
                    .select("ms")
                    .select("COALESCE(agg.amount, 0)")
                    .orderByAsc("ms");

            List<Tuple> rows = cb.getResultList();
            assertThat(rows).hasSize(4);
        } finally {
            em.close();
        }
    }

    /**
     * Bug: same {@code fromValues + leftJoinOn} shape, but wrapped
     * inside an outer {@code with(CashBalanceCte.class)…end()} block.
     * Crashes during SQL rendering; the test passes once the upstream
     * Blaze fix lands.
     */
    @Test
    @DisplayName("fromValues + leftJoinOn INSIDE outer CTE definition — crashes")
    void insideCteShapeCrashes() {
        EntityManager em = emf.createEntityManager();
        try {
            List<LocalDate> months = List.of(
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 2, 1),
                    LocalDate.of(2026, 3, 1),
                    LocalDate.of(2026, 4, 1));

            var cb = cbf.create(em, Tuple.class);

            // Inner aggregate CTE — fine on its own.
            cb.with(MonthlyAggCte.class)
                    .from(Movement.class, "m")
                    .groupBy("FUNCTION('TRUNC_MONTH', m.date)")
                    .bind("month")
                    .select("FUNCTION('TRUNC_MONTH', m.date)")
                    .bind("amount")
                    .select("SUM(m.amount)")
                    .end();

            // Outer CTE wrapping fromValues + leftJoinOn — boom.
            cb.with(CashBalanceCte.class)
                    .fromValues(Movement.class, "date", "ms", months)
                    .leftJoinOn(MonthlyAggCte.class, "agg")
                    .on("agg.month")
                    .eqExpression("ms")
                    .end()
                    .bind("month")
                    .select("ms")
                    .bind("balance")
                    .select("COALESCE(agg.amount, 0)")
                    .end();

            cb.from(CashBalanceCte.class, "c").select("c.month").select("c.balance");

            assertThatCode(cb::getResultList).doesNotThrowAnyException();
        } finally {
            em.close();
        }
    }
}
