package to.lova.blaze.issues.evm_remove_cascades_to_one;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/**
 * Owner of a plain {@code @ManyToOne} association: no {@code cascade},
 * no {@code orphanRemoval}. Plain JPA
 * {@code em.remove(em.find(Attendee.class, id))} deletes only this row
 * and leaves the referenced {@link BookingLine} intact.
 */
@Entity
public class Attendee {

    @Id
    @GeneratedValue
    Long id;

    @Column(nullable = false)
    String name;

    @ManyToOne
    BookingLine bookingLine;
}
