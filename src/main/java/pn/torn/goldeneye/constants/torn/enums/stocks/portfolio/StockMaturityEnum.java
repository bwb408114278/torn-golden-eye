package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Set;

/**
 * 股票成熟度枚举 - 标识股票历史数据/策略适配的成熟度阶段
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockMaturityEnum {
    /**
     * 未成熟 - 数据不足以用于策略
     */
    M0_UNMATURE("M0_UNMATURE", "未成熟"),
    /**
     * 早期 - 数据较少需谨慎
     */
    M1_EARLY("M1_EARLY", "早期"),
    /**
     * 暂定 - 历史不足一年但可用于策略适配
     */
    M2_PROVISIONAL("M2_PROVISIONAL", "暂定"),
    /**
     * 较成熟 - 历史充足可稳定使用
     */
    M3_SEASONED("M3_SEASONED", "较成熟"),
    /**
     * 成熟 - 历史数据完备
     */
    M4_MATURE("M4_MATURE", "成熟"),
    ;

    /**
     * 可用成熟度集合：暂定、较成熟、成熟
     */
    private static final Set<StockMaturityEnum> USABLE_MATURITIES = Set.of(
            M2_PROVISIONAL, M3_SEASONED, M4_MATURE
    );

    /**
     * 英文编码
     */
    private final String code;
    /**
     * 中文展示
     */
    private final String chineseDisplay;

    /**
     * 是否可用于策略适配（包含暂定、较成熟、成熟）
     *
     * @return 可用时返回true
     */
    public boolean isUsable() {
        return USABLE_MATURITIES.contains(this);
    }

    /**
     * 根据编码获取枚举值
     *
     * @param code 英文编码
     * @return 对应的枚举值
     * @throws IllegalArgumentException 编码不存在时抛出
     */
    public static StockMaturityEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知成熟度编码: " + code));
    }
}
