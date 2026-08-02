package to.lova.blaze.issues.paginated_disabled_count_total_size;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/**
 * Trivial entity used to fill more than one page. The reproducer seeds
 * three rows and queries with a page size of two, so a correct
 * {@code hasNextPage} computation must report a next page after the
 * first page.
 */
@Entity
public class Item {

    @Id
    @GeneratedValue
    Long id;

    @Column(nullable = false)
    int position;
}
