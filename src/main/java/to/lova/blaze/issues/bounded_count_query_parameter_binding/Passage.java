package to.lova.blaze.issues.bounded_count_query_parameter_binding;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

/**
 * Minimal entity for the bounded count query reproducer. The
 * {@code clientId} column is what the test filters on with a bound
 * parameter; {@code note} carries the second bound predicate in the
 * two-parameter variant.
 */
@Entity
public class Passage {

    @Id
    Long id;

    UUID clientId;

    String note;

    protected Passage() {
    }

    Passage(Long id, UUID clientId, String note) {
        this.id = id;
        this.clientId = clientId;
        this.note = note;
    }
}
