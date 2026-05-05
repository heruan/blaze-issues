package to.lova.blaze.issues.paginated_count_self_subview;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Minimal projection used as the {@code reversesMovement} subview
 * on {@link MovementView}. Using a different view type than
 * {@link MovementView} for the self-reference avoids the obvious
 * recursive cycle Blaze rejects at boot, but the fetch-join Blaze
 * still emits is enough to trigger the count-query bug we are
 * reproducing.
 */
@EntityView(Movement.class)
public interface MovementReferenceView {

    @IdMapping
    Long getId();

    LocalDate getDate();

    BigDecimal getAmount();
}
