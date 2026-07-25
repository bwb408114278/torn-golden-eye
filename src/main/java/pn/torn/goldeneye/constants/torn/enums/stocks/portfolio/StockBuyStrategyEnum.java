package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票买入策略编码枚举 - 标识组合中批次使用的买入策略类型
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockBuyStrategyEnum {
    /**
     * 深度均值回归 - 价格显著偏离均值时买入
     */
    DEEP_MEAN_REVERSION_BUY("DEEP_MEAN_REVERSION_BUY", "深度均值回归"),
    /**
     * 区间下沿买入 - 价格触及区间下沿时买入
     */
    RANGE_LOWER_BUY("RANGE_LOWER_BUY", "区间下沿买入"),
    /**
     * 严格反弹确认 - 需要反弹信号确认后才买入
     */
    STRICT_REBOUND_CONFIRM_BUY("STRICT_REBOUND_CONFIRM_BUY", "严格反弹确认"),
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
    public static StockBuyStrategyEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知买入策略编码: " + code));
    }
}
