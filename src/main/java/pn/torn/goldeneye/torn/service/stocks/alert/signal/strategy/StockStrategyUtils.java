package pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 股票买入策略公共工具类。
 * <p>
 * 提取多个买入策略中重复的判断逻辑，包括中期趋势保护判断和BigDecimal与0取较大值。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class StockStrategyUtils {
    /**
     * 中期趋势保护：MA7 / MA30 - 1 >= trendThreshold。
     * <p>
     * 判断短期均线相对长期均线的偏离是否在可接受范围内，ma7d或ma30d为null、
     * 或ma30d为零时返回false（fail-closed）。
     *
     * @param ma7d           近7日移动均价
     * @param ma30d          近30日移动均价
     * @param trendThreshold 趋势保护阈值
     * @param scale          BigDecimal运算精度
     * @return 趋势未破位时返回true
     */
    public static boolean isTrendProtected(BigDecimal ma7d, BigDecimal ma30d, BigDecimal trendThreshold, int scale) {
        if (ma7d == null || ma30d == null || ma30d.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        BigDecimal trendDeviation = ma7d.divide(ma30d, scale, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
        return trendDeviation.compareTo(trendThreshold) >= 0;
    }

    /**
     * 取BigDecimal与0的较大值。
     *
     * @param value 输入值
     * @return value > 0 时返回value，否则返回0
     */
    public static BigDecimal maxZero(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : BigDecimal.ZERO;
    }
}
