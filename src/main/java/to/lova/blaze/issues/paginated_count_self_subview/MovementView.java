package to.lova.blaze.issues.paginated_count_self_subview;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read-side projection for {@link Movement}. The {@link #getReversesMovement()}
 * subview is the relevant detail: declaring it as a Blaze subview
 * over the same entity (via a different view type, here
 * {@link MovementReferenceView}) makes Blaze auto-fetch it via a
 * LEFT JOIN with a Hibernate-generated alias (typically
 * {@code reversesMovement_1}). That alias is what the count query
 * emitted by the paginated builder later fails to resolve when the
 * outer CB also references the same association.
 */
@EntityView(Movement.class)
public interface MovementView {

    @IdMapping
    Long getId();

    LocalDate getDate();

    BigDecimal getAmount();

    MovementReferenceView getReversesMovement();
}
