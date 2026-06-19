package to.lova.blaze.issues.paginated_disabled_count_total_size;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazebit.persistence.Criteria;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.PagedList;
import com.blazebit.persistence.spi.CriteriaBuilderConfiguration;
import com.blazebit.persistence.view.ConfigurationProperties;
import com.blazebit.persistence.view.EntityViewManager;
import com.blazebit.persistence.view.EntityViewSetting;
import com.blazebit.persistence.view.Sorters;
import com.blazebit.persistence.view.spi.EntityViewConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceConfiguration;
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
 * Reproducer for a Blaze 1.6.18 pagination bug surfaced through the
 * GraphQL integration: when the keyset count query is disabled (which
 * {@code GraphQLEntityViewSupport.createPaginatedSetting} does
 * automatically whenever the GraphQL selection set omits the
 * {@code totalCount} field, via
 * {@link ConfigurationProperties#PAGINATION_DISABLE_COUNT_QUERY}), the
 * Relay {@code hasNextPage} flag is computed as {@code false} on a full
 * page even when further rows exist. A cursor loop that trusts
 * {@code hasNextPage} therefore stops after the first page and silently
 * drops every later page.
 *
 * <p><b>Root cause.</b> {@code GraphQLRelayPageInfo} computes
 * <pre>{@code
 * hasNextPage = size >= maxResults
 *     && (totalSize == -1 || firstResult + maxResults < totalSize);
 * }</pre>
 * The {@code totalSize == -1} branch is meant to mean "count unknown, so
 * assume there may be more". But with the count query disabled,
 * {@code PaginatedTypedQueryImpl.getResultList()} starts from
 * {@code totalSize = -1} and then raises it via
 * {@code totalSize = Math.max(totalSize, firstRow + result.size())}, so
 * {@link PagedList#getTotalSize()} returns the number of rows seen so far
 * (the page size on a full page) instead of {@code -1}. The formula then
 * evaluates {@code firstResult + maxResults < totalSize} as
 * {@code 0 + 2 < 2 == false} and reports no next page.
 *
 * <p>This test mirrors that {@code hasNextPage} formula verbatim. The
 * count-disabled case asserts the user-visible expectation (there IS a
 * next page) and therefore fails on 1.6.18; the count-enabled case is the
 * control and passes.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReproTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final int PAGE_SIZE = 2;

    EntityManagerFactory emf;
    CriteriaBuilderFactory cbf;
    EntityViewManager evm;

    @BeforeAll
    void setUp() {
        emf = new HibernatePersistenceConfiguration("repro")
                .managedClass(Item.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                .property(JdbcSettings.JAKARTA_JDBC_DRIVER, "org.postgresql.Driver")
                .property(SchemaToolingSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, "create-drop")
                .createEntityManagerFactory();

        CriteriaBuilderConfiguration cbConfig = Criteria.getDefault();
        cbf = cbConfig.createCriteriaBuilderFactory(emf);

        EntityViewConfiguration evConfig = com.blazebit.persistence.view.EntityViews.createDefaultConfiguration();
        evConfig.addEntityView(ItemView.class);
        evm = evConfig.createEntityViewManager(cbf);

        seed();
    }

    @AfterAll
    void tearDown() {
        if (emf != null) emf.close();
    }

    /** Three rows, so a page size of two leaves a non-empty second page. */
    private void seed() {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        for (int i = 1; i <= 3; i++) {
            Item item = new Item();
            item.position = i;
            em.persist(item);
        }
        tx.commit();
        em.close();
    }

    /**
     * Verbatim copy of the {@code hasNextPage} computation in
     * {@code com.blazebit.persistence.integration.graphql.GraphQLRelayPageInfo}
     * (Blaze 1.6.18), so the assertion below reflects exactly what a
     * GraphQL client observes on {@code pageInfo.hasNextPage}.
     */
    private static boolean graphqlHasNextPage(PagedList<?> page) {
        return page.size() >= page.getMaxResults()
                && (page.getTotalSize() == -1 || page.getFirstResult() + page.getMaxResults() < page.getTotalSize());
    }

    private PagedList<ItemView> firstPage(EntityManager em, boolean disableCountQuery) {
        EntityViewSetting<ItemView, ?> setting = EntityViewSetting.create(ItemView.class, 0, PAGE_SIZE);
        setting.addAttributeSorter("id", Sorters.ascending());
        if (disableCountQuery) {
            setting.setProperty(ConfigurationProperties.PAGINATION_DISABLE_COUNT_QUERY, Boolean.TRUE);
        }
        var cb = cbf.create(em, Item.class, "m");
        return (PagedList<ItemView>) evm.applySetting(setting, cb).getResultList();
    }

    /**
     * Bug: with the count query disabled, {@link PagedList#getTotalSize()}
     * returns the page size (2) instead of {@code -1}, violating its own
     * JavaDoc contract ("Returns the total size of the list or -1 if the
     * count query was disabled"). As a direct consequence the Relay
     * {@code hasNextPage} formula reports {@code false} on the full first
     * page even though a third row exists, so a cursor loop stops early.
     *
     * <p>Expected on a fixed release: {@code getTotalSize() == -1} and
     * therefore {@code hasNextPage == true}.
     */
    @Test
    @DisplayName("count query disabled — getTotalSize() breaks its -1 contract, breaking hasNextPage")
    void disabledCountQuery_totalSizeContractViolated() {
        EntityManager em = emf.createEntityManager();
        try {
            PagedList<ItemView> page = firstPage(em, true);

            assertThat(page).hasSize(PAGE_SIZE);
            assertThat(page.getTotalSize())
                    .as("getTotalSize() must be -1 when the count query is disabled (per its JavaDoc)")
                    .isEqualTo(-1);
            assertThat(graphqlHasNextPage(page))
                    .as("Relay hasNextPage must stay true while rows remain")
                    .isTrue();
        } finally {
            em.close();
        }
    }

    /**
     * Control: with the count query enabled (the default), the same first
     * page correctly reports {@code hasNextPage == true} and
     * {@code getTotalSize() == 3}. Confirms the disabled count query is
     * the sole trigger.
     */
    @Test
    @DisplayName("count query enabled — hasNextPage is correct (control)")
    void enabledCountQuery_hasNextPageCorrect() {
        EntityManager em = emf.createEntityManager();
        try {
            PagedList<ItemView> page = firstPage(em, false);

            assertThat(page).hasSize(PAGE_SIZE);
            assertThat(page.getTotalSize()).isEqualTo(3);
            assertThat(graphqlHasNextPage(page)).isTrue();
        } finally {
            em.close();
        }
    }
}
