package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票通知类型枚举 - 组合事件通知的分类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockNoticeTypeEnum {
    /**
     * 买入通知 - 批次买入时触发
     */
    BUY("BUY", "买入通知"),
    /**
     * 卖出通知 - 批次卖出时触发
     */
    SELL("SELL", "卖出通知"),
    /**
     * 每日摘要 - 每日组合汇总
     */
    DAILY_SUMMARY("DAILY_SUMMARY", "每日摘要"),
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
    public static StockNoticeTypeEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知通知类型编码: " + code));
    }
}
