package to.lova.blaze.issues.evm_remove_cascades_to_one;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.blazebit.persistence.view.UpdatableEntityView;

/**
 * Updatable view of {@link Attendee} with a typed setter taking a
 * reference view. {@code @UpdatableMapping} is left at its default
 * ({@code CascadeType.AUTO}), whose javadoc says DELETE cascading is
 * "determined based on the entity mapping" — and the entity mapping
 * configures no cascade at all. Yet
 * {@code evm.remove(em, AttendeeUpdatableView.class, id)} deletes the
 * referenced {@link BookingLine} row too.
 */
@EntityView(Attendee.class)
@UpdatableEntityView
public interface AttendeeUpdatableView {

    @IdMapping
    Long getId();

    String getName();

    void setName(String name);

    BookingLineRefView getBookingLine();

    void setBookingLine(BookingLineRefView bookingLine);
}
