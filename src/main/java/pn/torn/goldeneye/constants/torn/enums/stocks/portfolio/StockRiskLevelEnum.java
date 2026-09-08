package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票风险等级枚举 - 批次或持仓的当前风险评级
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockRiskLevelEnum {
    /**
     * 暂无明显风险
     */
    NONE("NONE", "暂无明显风险"),
    /**
     * 中等风险
     */
    MEDIUM("MEDIUM", "中等风险"),
    /**
     * 高风险
     */
    HIGH("HIGH", "高风险"),
    /**
     * α策略未评估 — α批次不参与旧版风险评级,禁止写入"不适用"伪业务值
     */
    ALPHA_NOT_EVALUATED("ALPHA_NOT_EVALUATED", "α未评估风险"),
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
    public static StockRiskLevelEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知风险等级编码: " + code));
    }
}
