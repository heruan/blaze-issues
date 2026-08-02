package to.lova.blaze.issues.entity_array_outer_alias_collision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Root entity of the views. The simple name {@code User} is the
 * relevant detail: Blaze derives the default query alias of the outer
 * root as {@code camelCase(SimpleName)} = {@code user}, which collides
 * with the {@link UserRole#user} field navigated inside the
 * entity-array predicate.
 *
 * <p>(The table is renamed only because {@code user} is a reserved
 * word in PostgreSQL — the bug is about the JPQL default alias, not
 * the table name.)
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue
    Long id;

    @Column(nullable = false)
    String name;
}
