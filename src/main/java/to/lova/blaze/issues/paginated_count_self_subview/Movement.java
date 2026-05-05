package to.lova.blaze.issues.paginated_count_self_subview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Self-referencing entity: a {@code Movement} optionally points back at
 * another {@code Movement} that it reverses (think accounting ledger
 * with reversal entries). The self-reference is what triggers the
 * Blaze fetch-join alias clash on the entity-view.
 */
@Entity
public class Movement {

    @Id
    @GeneratedValue
    Long id;

    @Column(nullable = false)
    LocalDate date;

    @Column(nullable = false)
    BigDecimal amount;

    @ManyToOne
    Movement reversesMovement;
}
