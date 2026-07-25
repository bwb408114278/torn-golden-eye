package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票月度状态枚举 - 月度配置/统计记录的生命周期状态
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockMonthlyStateStatusEnum {
    /**
     * 草稿 - 月度记录编辑中尚未确认
     */
    DRAFT("DRAFT", "草稿"),
    /**
     * 已确认 - 月度记录已确认生效
     */
    CONFIRMED("CONFIRMED", "已确认"),
    /**
     * 已退役 - 月度记录已归档退役
     */
    RETIRED("RETIRED", "已退役"),
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
    public static StockMonthlyStateStatusEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知月度状态编码: " + code));
    }
}
