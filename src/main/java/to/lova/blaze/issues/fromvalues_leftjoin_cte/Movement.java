package to.lova.blaze.issues.fromvalues_leftjoin_cte;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
public class Movement {

    @Id
    @GeneratedValue
    Long id;

    @Column(nullable = false)
    LocalDate date;

    @Column(nullable = false)
    BigDecimal amount;
}
