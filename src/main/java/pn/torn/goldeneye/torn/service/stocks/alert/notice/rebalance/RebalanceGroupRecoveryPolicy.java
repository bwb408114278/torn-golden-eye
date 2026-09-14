package pn.torn.goldeneye.torn.service.stocks.alert.notice.rebalance;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeRebalanceGroupStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;

import java.util.List;

/**
 * α换仓关联组异常恢复策略 - 判定组级回写行数不足或状态未知时的收敛决策
 * <p>
 * 组级成功、失败回写返回的行数不足时,不能把结果解释为"没有变化的成功",也不能盲目重发。
 * 收敛动作必须区分"本次流程仍持有组"和"本次流程未持有组":
 * <ul>
 *   <li>{@link RebalanceGroupRecoveryAction#FAILURE_WRITE}:Bot确认未发送且两腿仍由当前领取标识完整持有,
 *       只允许按完整组重写失败结果(数据库按相同尝试次数进入可重发或最终失败);</li>
 *   <li>{@link RebalanceGroupRecoveryAction#OWNED_UNKNOWN_RESULT}:Bot已成功且当前领取标识仍能证明持有组,
 *       收敛为{@code INCONSISTENT}人工核验,禁止再次调用Bot避免重复消息;</li>
 *   <li>{@link RebalanceGroupRecoveryAction#UNCLAIMED_CONVERGENCE}:组内不存在任何发送流程持有的
 *       {@code SENDING}状态,按无持有者安全条件收敛领取前结构异常;</li>
 *   <li>{@link RebalanceGroupRecoveryAction#NO_ACTION}:其他领取标识持有组、组已确认成功、
 *       组不可读或状态无法解释时只记录日志,不修改组也不调用Bot,保留人工核验。</li>
 * </ul>
 * 两腿尝试次数不同代表组事实已分叉,不允许按较小值猜测重试次数,也不允许按关联标识强行覆盖。
 * <p>
 * 本类只做纯决策,不访问数据库、不调用Bot、不写状态。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.13
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class RebalanceGroupRecoveryPolicy {

    /**
     * α换仓关联组必须包含的腿数。
     */
    private static final int REBALANCE_LEG_COUNT = 2;
    /**
     * 组级回写行数不足且关联组不可读时的统一原因。
     */
    private static final String UNREADABLE_GROUP_REASON =
            "α换仓关联组回写行数不足且关联组不可读,需人工核验";
    /**
     * 组已完整发送成功时的统一原因。
     */
    private static final String ALREADY_SENT_GROUP_REASON =
            "α换仓关联组已完整发送成功,不再收敛";
    /**
     * 组由其他发送流程持有时本次流程的动作原因。
     */
    private static final String OTHER_CLAIMANT_REASON =
            "α换仓关联组由其他发送流程持有,本次不调用Bot也不覆盖组状态,需人工核验";
    /**
     * Bot成功但成功回写不完整时的统一收敛原因。
     */
    private static final String UNKNOWN_RESULT_REASON =
            "α换仓消息可能已投递但组级成功回写不完整,按结果未知人工核验且禁止重复发送";
    /**
     * 两腿状态已分叉且无法按完整组重写失败结果时的统一收敛原因。
     */
    private static final String DIVERGED_GROUP_REASON =
            "α换仓关联组组内状态已分叉且无法按完整组重写失败结果,需人工核验";
    /**
     * 组无持有者且无法按正常组规则解释时的统一收敛原因。
     */
    private static final String UNCLAIMED_GROUP_REASON =
            "α换仓关联组无发送流程持有且无法按正常组规则解释,按人工核验收敛";

    /**
     * 判定组级回写不完整时的收敛动作。
     *
     * @param botSucceeded  本次Bot调用是否已确认成功
     * @param claimToken    本次领取标识
     * @param failureReason 本次失败或回写不足的原因
     * @param group         回写后重新读取的完整关联组
     * @return 收敛决策;组已完整发送成功且无需任何写入时返回{@code NO_ACTION}动作
     */
    public static RebalanceGroupRecovery decideWriteFailure(boolean botSucceeded, String claimToken,
                                                            String failureReason,
                                                            List<TornStockNoticeAuditDO> group) {
        if (CollectionUtils.isEmpty(group)) {
            return noAction(UNREADABLE_GROUP_REASON, failureReason);
        }
        if (group.stream().allMatch(RebalanceGroupStateResolver::isSent)) {
            return noAction(ALREADY_SENT_GROUP_REASON, failureReason);
        }
        if (hasOtherClaimantSendingLeg(group, claimToken)) {
            return noAction(OTHER_CLAIMANT_REASON, failureReason);
        }
        if (provesOwnership(group, claimToken)) {
            return new RebalanceGroupRecovery(
                    botSucceeded
                            ? RebalanceGroupRecoveryAction.OWNED_UNKNOWN_RESULT
                            : RebalanceGroupRecoveryAction.FAILURE_WRITE,
                    appendReason(botSucceeded ? UNKNOWN_RESULT_REASON : DIVERGED_GROUP_REASON, failureReason));
        }
        if (hasHeldGroupState(group)) {
            return noAction(DIVERGED_GROUP_REASON, failureReason);
        }
        return new RebalanceGroupRecovery(RebalanceGroupRecoveryAction.UNCLAIMED_CONVERGENCE,
                appendReason(UNCLAIMED_GROUP_REASON, failureReason));
    }

    /**
     * 构造不写入任何状态的收敛决策。
     *
     * @param baseReason    动作原因
     * @param failureReason 原始失败原因
     * @return 不写入动作的收敛决策
     */
    private static RebalanceGroupRecovery noAction(String baseReason, String failureReason) {
        return new RebalanceGroupRecovery(RebalanceGroupRecoveryAction.NO_ACTION,
                appendReason(baseReason, failureReason));
    }

    /**
     * 判断是否存在由其他领取标识持有的SENDING腿。
     *
     * @param group      完整关联组
     * @param claimToken 本次领取标识
     * @return 存在其他领取标识持有的SENDING腿返回true
     */
    private static boolean hasOtherClaimantSendingLeg(List<TornStockNoticeAuditDO> group, String claimToken) {
        return group.stream().anyMatch(leg -> isSending(leg)
                && (claimToken == null || !claimToken.equals(leg.getClaimToken())));
    }

    /**
     * 判断组内是否存在可解释的持有状态(仍有SENDING腿或组状态仍为SENDING)。
     *
     * @param group 完整关联组
     * @return 存在持有状态返回true
     */
    private static boolean hasHeldGroupState(List<TornStockNoticeAuditDO> group) {
        return group.stream().anyMatch(leg -> isSending(leg)
                || StockNoticeRebalanceGroupStatusEnum.SENDING.getCode().equals(leg.getRebalanceGroupStatus()));
    }

    /**
     * 判断本次领取标识是否仍能证明持有该关联组。
     * <p>
     * 与组级所有权收敛SQL保持同一口径:关联组恰好两腿、组状态均仍为SENDING、
     * 每条非SENT腿仍为SENDING且claim_token等于本次领取标识(等价于不存在其他领取标识持有的SENDING腿)。
     *
     * @param group      完整关联组
     * @param claimToken 本次领取标识
     * @return 能够证明持有返回true
     */
    private static boolean provesOwnership(List<TornStockNoticeAuditDO> group, String claimToken) {
        if (group.size() != REBALANCE_LEG_COUNT || claimToken == null || claimToken.isBlank()) {
            return false;
        }
        for (TornStockNoticeAuditDO leg : group) {
            if (leg == null
                    || !StockNoticeRebalanceGroupStatusEnum.SENDING.getCode()
                    .equals(leg.getRebalanceGroupStatus())) {
                return false;
            }
            if (RebalanceGroupStateResolver.isSent(leg)) {
                continue;
            }
            if (!isSending(leg) || !claimToken.equals(leg.getClaimToken())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断腿是否仍为发送中状态。
     *
     * @param leg 关联组中的一条通知
     * @return 处于SENDING返回true
     */
    private static boolean isSending(TornStockNoticeAuditDO leg) {
        return leg != null && StockNoticeStatusEnum.SENDING.getCode().equals(leg.getSendStatus());
    }

    /**
     * 把原始失败原因追加到收敛原因后,便于审计保留第一手失败信息。
     *
     * @param baseReason    收敛原因
     * @param failureReason 原始失败原因
     * @return 组合后的原因
     */
    private static String appendReason(String baseReason, String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            return baseReason;
        }
        return baseReason + "; 原始原因: " + failureReason;
    }

    /**
     * α换仓关联组异常恢复动作。
     */
    public enum RebalanceGroupRecoveryAction {
        /**
         * 按完整组重写失败结果:由数据库按两腿相同尝试次数进入FAILED_RETRYABLE或FAILED_FINAL。
         */
        FAILURE_WRITE,
        /**
         * 本流程仍持有组但Bot结果未知:收敛为组级INCONSISTENT人工核验终态,禁止再次调用Bot。
         */
        OWNED_UNKNOWN_RESULT,
        /**
         * 组内不存在任何持有者:按无持有者安全条件收敛领取前结构异常。
         */
        UNCLAIMED_CONVERGENCE,
        /**
         * 不写入任何状态:组由其他流程持有、已确认成功、不可读或状态无法解释,保留人工核验。
         */
        NO_ACTION
    }

    /**
     * α换仓关联组异常收敛决策。
     *
     * @param action       收敛动作
     * @param errorMessage 写入组级审计或日志的决策原因;不写入动作时用于诊断
     */
    public record RebalanceGroupRecovery(
            RebalanceGroupRecoveryAction action,
            String errorMessage) {
    }
}
