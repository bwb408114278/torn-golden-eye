package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票通知α换仓关联组状态枚举 - 一次换仓两腿组成的关联组的组级业务状态
 * <p>
 * α换仓由恰好两条通知组成({@code rebalanceAssociationId = ALPHA_REBALANCE:{rebalanceDecisionId}}),
 * 组级状态是判断"一次换仓是否完整送达"的唯一口径:单腿状态不得被解释为完整换仓通知状态。
 * 正常路径为
 * {@code PENDING → SENDING(组级领取) → SENT};
 * 失败路径为
 * {@code PENDING/FAILED_RETRYABLE → SENDING(组级领取) → FAILED_RETRYABLE/FAILED_FINAL};
 * {@link #INCONSISTENT} 是组内数据库状态无法按正常组规则解释时的组级异常终态,
 * 普通发送查询不得再次投递该组。
 * <p>
 * 该字段只用于α换仓组,普通通知固定为null。
 * <p>
 * 终态({@link #SENT}/{@link #FAILED_FINAL}/{@link #INCONSISTENT})的投递防护由
 * {@code TornStockNoticeAuditMapper.xml} 的可发送查询与组级收敛SQL以值域条件表达,
 * Java侧不得再实现一套并行的终态判定,避免两处口径漂移。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.13
 */
@Getter
@RequiredArgsConstructor
public enum StockNoticeRebalanceGroupStatusEnum {
    /**
     * 待发送 - 两腿完整且等待组级领取
     */
    PENDING("PENDING", "待发送"),
    /**
     * 发送中 - 组已被唯一发送流程领取,等待同一次Bot调用结果
     */
    SENDING("SENDING", "发送中"),
    /**
     * 已发送 - 两腿均已确认发送成功(终态)
     */
    SENT("SENT", "已发送"),
    /**
     * 可重发失败 - 两腿同一次发送失败且总尝试次数未达上限,后续按完整组重试
     */
    FAILED_RETRYABLE("FAILED_RETRYABLE", "可重发失败"),
    /**
     * 最终失败 - 达到总尝试上限,或缺腿/重复腿/字段冲突/不可解释状态(终态,人工核验)
     */
    FAILED_FINAL("FAILED_FINAL", "最终失败"),
    /**
     * 组状态不可解释 - 组内数据库状态无法按正常组规则解释(终态,人工核验)
     */
    INCONSISTENT("INCONSISTENT", "组状态不可解释"),
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
    public static StockNoticeRebalanceGroupStatusEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知α换仓组状态编码: " + code));
    }

}
