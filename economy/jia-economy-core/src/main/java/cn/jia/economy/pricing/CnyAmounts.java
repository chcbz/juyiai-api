package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** RMB cost precision and integer-wen quoting; no silver conversion or runtime FX. */
public final class CnyAmounts {
    private CnyAmounts() { }

    /** Round only the final cost being posted, not each token category. */
    public static BigDecimal recordedCost(BigDecimal yuan) {
        return nonNegative(yuan).setScale(6, RoundingMode.HALF_UP);
    }

    /** Quote directly from the unrounded estimate: 1 yuan = 100 wen. */
    public static long quoteWen(BigDecimal estimatedYuan) {
        return quoteWen(estimatedYuan, BigDecimal.ONE);
    }

    /** Exact rational yuan; avoid rounding an intermediate weighted mean onto the wrong side of a wen. */
    static long quoteWen(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() <= 0) throw new IllegalArgumentException("Invalid denominator");
        return nonNegative(numerator).movePointRight(2).divide(denominator, 0, RoundingMode.CEILING).longValueExact();
    }

    static BigDecimal nonNegative(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) throw new IllegalArgumentException("Cost cannot be negative");
        return amount;
    }
}
