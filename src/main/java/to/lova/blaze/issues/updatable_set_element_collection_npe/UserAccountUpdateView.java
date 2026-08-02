package to.lova.blaze.issues.updatable_set_element_collection_npe;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.blazebit.persistence.view.UpdatableEntityView;
import java.util.Set;

/**
 * Updatable view with the default {@link com.blazebit.persistence.view.FlushStrategy#QUERY}
 * flush strategy. Flushing an add/remove diff on {@link #getRoles()}
 * while the owning entity is NOT in the persistence context NPEs in
 * {@code CollectionAttributeFlusher.getAddedAndRemovedElementsForInverseFlusher}.
 */
@EntityView(UserAccount.class)
@UpdatableEntityView
public interface UserAccountUpdateView {

    @IdMapping
    Long getId();

    Set<String> getRoles();

    void setRoles(Set<String> roles);
}
