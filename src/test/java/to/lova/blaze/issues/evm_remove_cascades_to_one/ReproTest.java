package to.lova.blaze.issues.evm_remove_cascades_to_one;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Reproducer for a Blaze 1.6.20 entity-view removal bug:
 * {@code evm.remove(EntityManager, Class, Object id)} — the
 * delete-by-id variant that does not load the view — cascade-deletes
 * every non-null {@code @ManyToOne} reference reachable through a
 * setter of the updatable view, even though the JPA mapping configures
 * NO cascade at all and {@code @UpdatableMapping} uses the default
 * {@code CascadeType.AUTO}, whose javadoc says DELETE cascading is
 * "determined based on the entity mapping".
 *
 * <p>In the SQL logs the root delete reads the FK back and then
 * deletes the referenced row:
 * <pre>
 * delete from Attendee where id=? returning bookingLine_id
 * delete from BookingLine where id=?
 * </pre>
 *
 * <p>The plain JPA control flow
 * {@code em.remove(em.find(Attendee.class, id))} on identical data
 * leaves the {@code BookingLine} intact, proving the entity mapping
 * itself carries no cascade semantics — see
 * {@link #plainJpaRemoveLeavesManyToOneReferenceIntact()}.
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
                .managedClass(BookingLine.class)
                .managedClass(Attendee.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                .property(JdbcSettings.JAKARTA_JDBC_DRIVER, "org.postgresql.Driver")
                .property(JdbcSettings.SHOW_SQL, "true")
                .property(JdbcSettings.FORMAT_SQL, "false")
                .property(SchemaToolingSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, "create-drop")
                .createEntityManagerFactory();

        CriteriaBuilderConfiguration cbConfig = Criteria.getDefault();
        cbf = cbConfig.createCriteriaBuilderFactory(emf);

        EntityViewConfiguration evConfig = EntityViews.createDefaultConfiguration();
        evConfig.addEntityView(BookingLineRefView.class);
        evConfig.addEntityView(AttendeeUpdatableView.class);
        evm = evConfig.createEntityViewManager(cbf);
    }

    @AfterAll
    void tearDown() {
        if (emf != null) emf.close();
    }

    private record Seed(Long bookingLineId, Long attendeeId) {}

    private Seed seed(String label, String name) {
        EntityManager em = emf.createEntityManager();
        try {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            BookingLine bookingLine = new BookingLine();
            bookingLine.label = label;
            em.persist(bookingLine);
            Attendee attendee = new Attendee();
            attendee.name = name;
            attendee.bookingLine = bookingLine;
            em.persist(attendee);
            tx.commit();
            return new Seed(bookingLine.id, attendee.id);
        } finally {
            em.close();
        }
    }

    /**
     * Bug: delete-by-id through the entity-view manager. Expected:
     * only the Attendee row is deleted, exactly like the plain JPA
     * control test. Observed on 1.6.20: the root delete comes back as
     * {@code delete from Attendee ... returning bookingLine_id}
     * followed by {@code delete from BookingLine where id=?} — the
     * uncascaded {@code @ManyToOne} reference is deleted too.
     */
    @Test
    @DisplayName("evm.remove by id deletes the uncascaded @ManyToOne reference — bug")
    void evmRemoveByIdDeletesManyToOneReference() {
        Seed seed = seed("evm-remove-line", "evm-remove-attendee");

        EntityManager em = emf.createEntityManager();
        try {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            evm.remove(em, AttendeeUpdatableView.class, seed.attendeeId());
            em.flush();
            em.clear();
            tx.commit();

            assertThat(em.find(Attendee.class, seed.attendeeId()))
                    .as("the Attendee row itself must be deleted")
                    .isNull();
            assertThat(em.find(BookingLine.class, seed.bookingLineId()))
                    .as("BookingLine must survive: no cascade is configured on the "
                            + "@ManyToOne and CascadeType.AUTO defers to the entity mapping")
                    .isNotNull();
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }
    }

    /**
     * Control: identical data deleted through plain JPA. The
     * BookingLine survives, proving the JPA mapping itself has no
     * cascade semantics and the deletion of the referenced row is
     * introduced by the entity-view removal alone.
     */
    @Test
    @DisplayName("plain em.remove leaves the @ManyToOne reference intact — control")
    void plainJpaRemoveLeavesManyToOneReferenceIntact() {
        Seed seed = seed("jpa-remove-line", "jpa-remove-attendee");

        EntityManager em = emf.createEntityManager();
        try {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            em.remove(em.find(Attendee.class, seed.attendeeId()));
            em.flush();
            em.clear();
            tx.commit();

            assertThat(em.find(Attendee.class, seed.attendeeId()))
                    .as("the Attendee row itself must be deleted")
                    .isNull();
            assertThat(em.find(BookingLine.class, seed.bookingLineId()))
                    .as("BookingLine survives a plain JPA remove of the Attendee")
                    .isNotNull();
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }
    }
}
