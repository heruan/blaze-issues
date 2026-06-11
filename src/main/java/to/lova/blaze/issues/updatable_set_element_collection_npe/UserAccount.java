package to.lova.blaze.issues.updatable_set_element_collection_npe;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.util.HashSet;
import java.util.Set;

/**
 * Entity with an {@code @ElementCollection} of a basic type declared
 * as a {@code Set}. The {@code Set} shape is the relevant detail: a
 * {@code List} never triggers the bug because bag instantiators exit
 * {@code CollectionAttributeFlusher.getFusedOperations} early via
 * {@code collectionInstantiator.allowsDuplicates()}.
 *
 * <p>(Named {@code UserAccount} only because {@code user} is a
 * reserved word in PostgreSQL.)
 */
@Entity
public class UserAccount {

    @Id
    @GeneratedValue
    Long id;

    @Column(nullable = false)
    String name;

    @ElementCollection
    Set<String> roles = new HashSet<>();
}
