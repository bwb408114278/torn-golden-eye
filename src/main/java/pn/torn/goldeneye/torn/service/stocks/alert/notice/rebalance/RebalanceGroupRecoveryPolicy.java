package pn.torn.goldeneye.torn.service.stocks.alert.notice.rebalance;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;

import java.util.List;

/**
 * α换仓关联组异常恢复策略 - 判定组级回写行数不足或状态未知时的收敛决策
 * <p>
 * 组级成功、失败回写返回的行数不足时,不能把结果解释为"没有变化的成功",也不能盲目重发:
 * <ul>
 *   <li>Bot已成功但成功回写行数不足:消息可能已经投递,按"结果未知"收敛为
 *       {@code INCONSISTENT},禁止再次调用Bot避免重复消息;</li>
 *   <li>Bot失败且失败回写行数不足:消息确认未投递,只允许在两腿仍为同一领取者持有的
 *       {@code SENDING}时按完整组重新写失败结果(数据库按尝试次数进入可重发或最终失败);</li>
 *   <li>其余组内状态一律收敛为{@code INCONSISTENT}人工核验,禁止把单腿当作完整组处理。</li>
 * </ul>
 * 两腿尝试次数不同代表组事实已分叉,不允许按较小值猜测重试次数。
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
     * 组级回写行数不足且关联组不可读时的统一收敛原因。
     */
    private static final String UNREADABLE_GROUP_REASON =
            "α换仓关联组回写行数不足且关联组不可读,需人工核验";
    /**
     * Bot成功但成功回写不完整时的统一收敛原因。
     */
    private static final String UNKNOWN_RESULT_REASON =
            "α换仓消息可能已投递但组级成功回写不完整,按结果未知人工核验且禁止重复发送";
    /**
     * 两腿状态已分叉时的统一收敛原因。
     */
    private static final String DIVERGED_GROUP_REASON =
            "α换仓关联组组内状态已分叉且无法按完整组重写失败结果,需人工核验";

    /**
     * 判定组级回写不完整时的收敛动作。
     *
     * @param botSucceeded  本次Bot调用是否已确认成功
     * @param claimToken    本次领取标识
     * @param failureReason 本次失败或回写不足的原因
     * @param group         回写后重新读取的完整关联组
     * @return 收敛决策;无需收敛时返回null
     */
    public static RebalanceGroupRecovery decideWriteFailure(boolean botSucceeded, String claimToken,
                                                            String failureReason,
                                                            List<TornStockNoticeAuditDO> group) {
        if (CollectionUtils.isEmpty(group)) {
            return new RebalanceGroupRecovery(RebalanceGroupRecoveryAction.INCONSISTENT,
                    appendReason(UNREADABLE_GROUP_REASON, failureReason));
        }
        if (group.stream().allMatch(RebalanceGroupStateResolver::isSent)) {
            return null;
        }
        if (!botSucceeded && isWholeGroupHeldByClaim(group, claimToken)) {
            return new RebalanceGroupRecovery(RebalanceGroupRecoveryAction.FAILURE_WRITE,
                    appendReason(DIVERGED_GROUP_REASON, failureReason));
        }
        return new RebalanceGroupRecovery(RebalanceGroupRecoveryAction.INCONSISTENT,
                appendReason(botSucceeded ? UNKNOWN_RESULT_REASON : DIVERGED_GROUP_REASON, failureReason));
    }

    /**
     * 判断关联组是否为完整两腿且两腿都由本次领取者持有且仍处于SENDING。
     *
     * @param group      完整关联组
     * @param claimToken 本次领取标识
     * @return 完整两腿且领取标识一致时返回true
     */
    private static boolean isWholeGroupHeldByClaim(List<TornStockNoticeAuditDO> group, String claimToken) {
        if (group.size() != 2 || claimToken == null || claimToken.isBlank()) {
            return false;
        }
        for (TornStockNoticeAuditDO leg : group) {
            if (leg == null || !StockNoticeStatusEnum.SENDING.getCode().equals(leg.getSendStatus())
                    || !claimToken.equals(leg.getClaimToken())) {
                return false;
            }
        }
        return true;
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
         * 按完整组重写失败结果:由数据库按两腿尝试次数进入FAILED_RETRYABLE或FAILED_FINAL。
         */
        FAILURE_WRITE,
        /**
         * 收敛为组级INCONSISTENT人工核验终态,禁止再次调用Bot。
         */
        INCONSISTENT
    }

    /**
     * α换仓关联组异常收敛决策。
     *
     * @param action       收敛动作
     * @param errorMessage 写入组级审计的收敛原因
     */
    public record RebalanceGroupRecovery(
            RebalanceGroupRecoveryAction action,
            String errorMessage) {
    }
}
