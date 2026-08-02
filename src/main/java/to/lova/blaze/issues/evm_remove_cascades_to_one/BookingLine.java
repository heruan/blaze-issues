package to.lova.blaze.issues.evm_remove_cascades_to_one;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/**
 * Target of a plain {@code @ManyToOne} association with NO cascade and
 * NO orphanRemoval anywhere. Deleting an {@link Attendee} must never
 * delete this row.
 */
@Entity
public class BookingLine {

    @Id
    @GeneratedValue
    Long id;

    @Column(nullable = false)
    String label;
}
