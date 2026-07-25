package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票通知状态枚举 - 通知的发送生命周期状态
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockNoticeStatusEnum {
    /**
     * 待发送 - 通知已创建尚未推送
     */
    PENDING("PENDING", "待发送"),
    /**
     * 已发送 - 推送成功
     */
    SENT("SENT", "已发送"),
    /**
     * 发送失败 - 推送失败
     */
    FAILED("FAILED", "发送失败"),
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
    public static StockNoticeStatusEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知通知状态编码: " + code));
    }
}
