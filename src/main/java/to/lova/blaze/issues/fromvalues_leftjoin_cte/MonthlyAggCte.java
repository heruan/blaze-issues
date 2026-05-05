package to.lova.blaze.issues.fromvalues_leftjoin_cte;

import com.blazebit.persistence.CTE;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDate;

@CTE
@Entity
public class MonthlyAggCte {

    @Id
    LocalDate month;

    BigDecimal amount;
}
