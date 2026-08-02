package to.lova.blaze.issues.evm_remove_cascades_to_one;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;

/**
 * Plain read-only reference view of {@link BookingLine}, used as the
 * type of the {@code bookingLine} setter on
 * {@link AttendeeUpdatableView}.
 */
@EntityView(BookingLine.class)
public interface BookingLineRefView {

    @IdMapping
    Long getId();
}
