package to.lova.blaze.issues.entity_array_outer_alias_collision;

import com.blazebit.persistence.view.EntityView;
import com.blazebit.persistence.view.IdMapping;
import com.blazebit.persistence.view.Mapping;
import java.util.Set;

/**
 * Buggy variant: entity-array bracket sugar. Per the Blaze core docs,
 * inside the brackets "the implicit root for path expressions is the
 * joined entity itself", so {@code user.id} should resolve to
 * {@code UserRole.user.id}. Instead the token {@code user} wins
 * resolution against the identically-named default alias of the outer
 * root {@link User}, the ON clause degenerates into the tautology
 * {@code u1_0.id = u1_0.id}, and every row silently receives the
 * union of all rows' roles.
 */
@EntityView(User.class)
public interface UserView {

    @IdMapping
    Long getId();

    String getName();

    @Mapping("UserRole[user.id = VIEW(id)].role")
    Set<String> getRoles();
}
