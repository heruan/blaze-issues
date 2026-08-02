package to.lova.blaze.issues.entity_array_outer_alias_collision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/**
 * Association entity joined via the entity-array expression
 * {@code UserRole[user.id = VIEW(id)]}. The field name {@code user}
 * is REQUIRED for the reproducer: it coincides with the default alias
 * {@code user} of the outer root {@link User}, and the predicate's
 * first path segment resolves against that outer alias instead of the
 * joined {@code UserRole} itself.
 */
@Entity
public class UserRole {

    @Id
    @GeneratedValue
    Long id;

    @ManyToOne(optional = false)
    User user;

    @Column(nullable = false)
    String role;
}
