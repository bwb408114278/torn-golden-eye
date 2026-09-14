package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票通知状态枚举 - 通知的发送生命周期状态
 * <p>
 * 正常生命周期为:
 * {@code PENDING → SENDING(领取) → SENT} 或
 * {@code PENDING/FAILED_RETRYABLE → SENDING(领取) → FAILED_RETRYABLE/FAILED_FINAL}。
 * {@link #SENDING} 是数据库级领取状态,只有领取成功者才能调用Bot并回写终态;
 * {@link #FAILED_RETRYABLE} 由后续调度自动重发,达到总尝试上限后进入 {@link #FAILED_FINAL}。
 * {@link #FAILED} 为自动重发实现之前的历史终态,不再产生新记录,仅保留读取兼容。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockNoticeStatusEnum {
    /**
     * 待发送 - 通知已创建尚未推送,可被发送流程领取
     */
    PENDING("PENDING", "待发送"),
    /**
     * 发送中 - 已被唯一发送流程领取并计入一次Bot调用,等待回写终态
     */
    SENDING("SENDING", "发送中"),
    /**
     * 已发送 - 推送成功(终态)
     */
    SENT("SENT", "已发送"),
    /**
     * 可重发失败 - 推送失败或结果未知,后续调度自动重发
     */
    FAILED_RETRYABLE("FAILED_RETRYABLE", "可重发失败"),
    /**
     * 最终失败 - 达到总尝试上限或进入人工核验(终态,不再自动发送)
     */
    FAILED_FINAL("FAILED_FINAL", "最终失败"),
    /**
     * 发送失败 - 自动重发实现之前的历史终态,不再产生新记录
     */
    FAILED("FAILED", "发送失败(历史终态)"),
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

    /**
     * 判断状态是否可被发送流程领取。
     * <p>
     * 只有 {@link #PENDING} 与 {@link #FAILED_RETRYABLE} 可领取:{@link #SENDING} 已被其他流程持有,
     * {@link #SENT}/{@link #FAILED_FINAL}/{@link #FAILED} 为终态不得再次发送。
     *
     * @param code 状态编码
     * @return 可领取返回true
     */
    public static boolean isClaimable(String code) {
        return PENDING.code.equals(code) || FAILED_RETRYABLE.code.equals(code);
    }
}
