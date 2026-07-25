package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票组合决策枚举 - 资格评估后对组合建立做出的最终决策
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockPortfolioDecisionEnum {
    /**
     * 正式建立 - 进入正式组合
     */
    FORMAL("FORMAL", "正式建立"),
    /**
     * 影子建立 - 进入影子组合模拟
     */
    SHADOW("SHADOW", "影子建立"),
    /**
     * 拒绝建立 - 不建立任何组合
     */
    REJECTED("REJECTED", "拒绝建立"),
    ;

    /**
     * 英文编码
     */
    private final String code;
    /**
     * 中文展示
     */
    private final String chineseDisplay;

    /**
     * 根据编码获取枚举值
     *
     * @param code 英文编码
     * @return 对应的枚举值
     * @throws IllegalArgumentException 编码不存在时抛出
     */
    public static StockPortfolioDecisionEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知组合决策编码: " + code));
    }
}
