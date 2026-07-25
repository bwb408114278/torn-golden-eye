package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票取消原因枚举 - 批次在买入前被取消的具体原因
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockCancelReasonEnum {
    /**
     * 买入数据过期
     */
    ENTRY_DATA_STALE("ENTRY_DATA_STALE", "买入数据过期"),
    /**
     * 买入价格偏离过大
     */
    ENTRY_PRICE_DEVIATION("ENTRY_PRICE_DEVIATION", "买入价格偏离过大"),
    /**
     * 同股已有活跃批次
     */
    SAME_STOCK_ACTIVE("SAME_STOCK_ACTIVE", "同股已有活跃批次"),
    /**
     * 无可用槽位
     */
    NO_AVAILABLE_SLOT("NO_AVAILABLE_SLOT", "无可用槽位"),
    /**
     * 槽位复核失败
     */
    SLOT_RECHECK_FAILED("SLOT_RECHECK_FAILED", "槽位复核失败"),
    /**
     * 冷却中
     */
    COOLDOWN_ACTIVE("COOLDOWN_ACTIVE", "冷却中"),
    /**
     * 未复位
     */
    RESET_NOT_OBSERVED("RESET_NOT_OBSERVED", "未复位"),
    /**
     * 风格未就绪
     */
    STYLE_NOT_READY("STYLE_NOT_READY", "风格未就绪"),
    /**
     * 数据未就绪
     */
    DATA_NOT_READY("DATA_NOT_READY", "数据未就绪"),
    /**
     * 手动取消
     */
    MANUAL_CANCEL("MANUAL_CANCEL", "手动取消"),
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
    public static StockCancelReasonEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知取消原因编码: " + code));
    }
}
