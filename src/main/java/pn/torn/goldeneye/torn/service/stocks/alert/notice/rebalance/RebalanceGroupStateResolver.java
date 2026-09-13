package pn.torn.goldeneye.torn.service.stocks.alert.notice.rebalance;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendRecorder;

import java.util.ArrayList;
import java.util.List;

/**
 * α换仓关联组状态解析器 - 解析完整两腿的数据库状态组合并给出本次可发送结论
 * <p>
 * 组状态解析是"能否发送"的唯一判定来源:只有两腿都仍可领取时才是完整可发送组;
 * 单腿已SENT而另一腿可重试、两腿生命周期已分叉等状态都属于不可解释状态,
 * 必须收敛为组级异常终态,禁止只发送剩余腿,也禁止把单腿状态解释为完整换仓送达。
 * <p>
 * 本类只做纯规则解析,不访问数据库、不调用Bot、不写状态。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.13
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class RebalanceGroupStateResolver {

    /**
     * 解析关联组两腿的发送状态组合。
     *
     * @param sellLeg 原仓卖出腿
     * @param buyLeg  新仓买入腿
     * @return 组状态解析结果
     */
    public static RebalanceGroupState resolve(TornStockNoticeAuditDO sellLeg, TornStockNoticeAuditDO buyLeg) {
        List<TornStockNoticeAuditDO> legs = List.of(sellLeg, buyLeg);
        List<Long> sendableLegIds = new ArrayList<>(2);
        int sentCount = 0;
        int terminalNonSentCount = 0;
        for (TornStockNoticeAuditDO leg : legs) {
            if (isSent(leg)) {
                sentCount++;
                continue;
            }
            if (isSendable(leg)) {
                if (leg.getId() != null) {
                    sendableLegIds.add(leg.getId());
                }
            } else {
                terminalNonSentCount++;
            }
        }
        if (sendableLegIds.size() == legs.size()) {
            return new RebalanceGroupState(sendableLegIds, false, null, false);
        }
        if (sentCount == legs.size() || (sendableLegIds.isEmpty() && sentCount == 0
                && terminalNonSentCount == legs.size())) {
            return new RebalanceGroupState(List.of(), false, null, true);
        }
        return new RebalanceGroupState(List.of(), true, "关联组状态无法按正常组规则解释: sellStatus="
                + statusOf(sellLeg) + ", buyStatus=" + statusOf(buyLeg), false);
    }

    /**
     * 判断腿是否处于已发送终态。
     *
     * @param leg 关联组中的一条通知
     * @return 已发送返回true
     */
    public static boolean isSent(TornStockNoticeAuditDO leg) {
        return leg != null && StockNoticeStatusEnum.SENT.getCode().equals(leg.getSendStatus());
    }

    /**
     * 判断腿是否仍可被组级领取发送。
     *
     * @param leg 关联组中的一条通知
     * @return 可发送返回true
     */
    private static boolean isSendable(TornStockNoticeAuditDO leg) {
        if (leg == null || !StockNoticeStatusEnum.isClaimable(leg.getSendStatus())) {
            return false;
        }
        Integer attemptCount = leg.getSendAttemptCount();
        return attemptCount == null || attemptCount < StockNoticeSendRecorder.MAX_SEND_ATTEMPTS;
    }

    /**
     * 读取腿的发送状态,腿为空时返回null。
     *
     * @param leg 关联组中的一条通知
     * @return 发送状态
     */
    private static String statusOf(TornStockNoticeAuditDO leg) {
        return leg == null ? null : leg.getSendStatus();
    }

    /**
     * α换仓关联组状态解析结果。
     *
     * @param sendableLegIds 本次仍可领取发送的腿ID(按SELL、BUY顺序);不可发送时为空列表
     * @param inconsistent   组内状态不可按正常组规则解释时为true,必须收敛为组级异常终态
     * @param reason         不可解释原因;正常时为null
     * @param nothingToDo    两腿均已进入可解释终态,本次既不需要发送也不需要收敛
     */
    public record RebalanceGroupState(
            List<Long> sendableLegIds,
            boolean inconsistent,
            String reason,
            boolean nothingToDo) {
    }
}
