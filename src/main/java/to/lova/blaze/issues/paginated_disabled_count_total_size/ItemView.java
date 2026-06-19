package to.lova.blaze.issues.paginated_disabled_count_total_size;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;

/**
 * Minimal read projection for {@link Item}.
 */
@EntityView(Item.class)
public interface ItemView {

    @IdMapping
    Long getId();

    int getPosition();
}
