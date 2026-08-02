package to.lova.blaze.issues.entity_array_outer_alias_collision;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.FetchStrategy;
import com.blazebit.persistence.view.IdMapping;
import com.blazebit.persistence.view.MappingCorrelatedSimple;
import java.util.Set;

/**
 * Control variant: the same join expressed with an explicit correlated
 * mapping instead of the entity-array bracket sugar. Despite the
 * identical field-name/outer-alias coincidence ({@code user.id} in the
 * correlation expression), it resolves correctly against the
 * correlated {@link UserRole} and returns isolated per-user roles —
 * proving the bracket-sugar code path is broken, not the model.
 */
@EntityView(User.class)
public interface UserCorrelatedView {

    @IdMapping
    Long getId();

    String getName();

    @MappingCorrelatedSimple(
            correlated = UserRole.class,
            correlationBasis = "id",
            correlationExpression = "user.id = correlationKey",
            correlationResult = "role",
            fetch = FetchStrategy.JOIN)
    Set<String> getRoles();
}
