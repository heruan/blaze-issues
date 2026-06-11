package to.lova.blaze.issues.updatable_set_element_collection_npe;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.FlushStrategy;
import com.blazebit.persistence.view.IdMapping;
import com.blazebit.persistence.view.UpdatableEntityView;
import java.util.Set;

/**
 * Identical shape to {@link UserAccountUpdateView} but flushed with
 * {@link FlushStrategy#ENTITY}: the flusher loads the owning entity
 * first and never enters the query-flush fused-operations codepath,
 * so the same update flow succeeds. Proves the bug is scoped to
 * {@code FlushStrategy.QUERY}.
 */
@EntityView(UserAccount.class)
@UpdatableEntityView(strategy = FlushStrategy.ENTITY)
public interface UserAccountEntityFlushView {

    @IdMapping
    Long getId();

    Set<String> getRoles();

    void setRoles(Set<String> roles);
}
