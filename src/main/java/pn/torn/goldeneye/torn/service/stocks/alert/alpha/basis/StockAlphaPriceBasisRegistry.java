package pn.torn.goldeneye.torn.service.stocks.alert.alpha.basis;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * α策略价格口径注册表 - 两套口径的唯一枚举与查找入口。
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
     * 全部注册口径,顺序固定为生产口径在前、观察口径在后。
     */
    private static final List<StockAlphaPriceBasis> ALL = List.of(PREVIOUS_CLOSE, LATEST_PRICE);

    /**
     * 返回全部已注册口径。
     *
     * @return 不可变的口径列表
     */
    public static List<StockAlphaPriceBasis> all() {
        return ALL;
    }

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

    /**
     * 按编码查找口径。
     *
     * @param code 口径编码
     * @return 对应口径实现
     * @throws IllegalArgumentException 编码为空或未注册时抛出
     */
    public static StockAlphaPriceBasis of(String code) {
        return ALL.stream().filter(basis -> basis.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知α价格口径编码: " + code));
    }
}
