package pn.torn.goldeneye.torn.service.stocks.alert.alpha.basis;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * α策略价格口径注册表 - 两套口径的唯一枚举入口。
 * <p>
 * 生产目标固定取{@link #productionBasis()};观察口径固定取{@link #observationBasis()},
 * 只写观察列且恒不影响生产。业务类不得内联 if/else 判断口径,也不得自行 new 实现类。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.18
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockAlphaPriceBasisRegistry {

    /**
     * 生产口径实现。
     */
    private static final StockAlphaPriceBasis PREVIOUS_CLOSE = new StockAlphaPreviousCloseBasis();
    /**
     * 观察口径实现。
     */
    private static final StockAlphaPriceBasis LATEST_PRICE = new StockAlphaLatestPriceBasis();

    /**
     * 返回生产决策唯一使用的口径。
     *
     * @return 生产口径实现
     */
    public static StockAlphaPriceBasis productionBasis() {
        return PREVIOUS_CLOSE;
    }

    /**
     * 返回只写观察列的观察口径。
     *
     * @return 观察口径实现
     */
    public static StockAlphaPriceBasis observationBasis() {
        return LATEST_PRICE;
    }
}
