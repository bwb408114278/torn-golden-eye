package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票策略适配风格枚举 - 正式组合使用的股票风格分类，与旧 {@code StockPersonalityEnum} 分开维护
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockStrategyFitEnum {
    /**
     * 持续下行
     */
    DECLINER("DECLINER", "持续下行"),
    /**
     * 弱势
     */
    WEAK("WEAK", "弱势"),
    /**
     * 窄幅震荡
     */
    NARROW("NARROW", "窄幅震荡"),
    /**
     * 区间震荡
     */
    RANGING("RANGING", "区间震荡"),
    /**
     * 稳健
     */
    STEADY("STEADY", "稳健"),
    /**
     * 强势
     */
    STRONG("STRONG", "强势"),
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
    public static StockStrategyFitEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知策略适配风格编码: " + code));
    }
}
