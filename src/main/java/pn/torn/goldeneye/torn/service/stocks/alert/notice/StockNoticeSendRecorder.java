package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 股票通知发送记录器 - 发送前领取、冻结最终payload并按发送结果回写可重发终态。
 * <p>
 * 普通通知与α换仓关联组共用本记录器,保证"数据库级领取""冻结行数必须完整""回写必须绑定领取标识"
 * 的口径只有一套实现:
 * <ul>
 *   <li>领取:只有PENDING/FAILED_RETRYABLE且未达3次总尝试上限的通知可被领取,领取即累计一次尝试;
 *       领取行数不足时释放本次领取并禁止调用Bot,避免重复消息</li>
 *   <li>冻结:只能冻结本领取者持有的SENDING通知,冻结UPDATE行数不等于通知数时禁止调用Bot</li>
 *   <li>回写:状态与领取标识必须同时匹配,旧领取者不能覆盖新状态;失败未达上限写FAILED_RETRYABLE
 *       由后续调度自动重发,达到上限写FAILED_FINAL终态;状态回写异常不回滚交易事实,通知停留SENDING
 *       并由领取租约超时恢复为可重发或最终失败</li>
 * </ul>
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockNoticeSendRecorder {
    /**
     * 每条通知的总尝试次数上限(首次1次 + 自动重发2次)。
     * <p>
     * 领取、失败回写与租约恢复的SQL使用同一上限值,修改时必须同步
     * {@code TornStockNoticeAuditMapper.xml} 中的判断。
     */
    public static final int MAX_SEND_ATTEMPTS = 3;

    private final TornStockNoticeAuditDAO noticeAuditDao;

    /**
     * 生成本次发送的领取标识。
     * <p>
     * 每次发送流程使用独立标识,冻结与终态回写都必须携带该标识,保证旧领取者无法覆盖新状态。
     *
     * @return 领取标识
     */
    public String newClaimToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * 原子领取一批通知。
     * <p>
     * 领取行数必须完整等于期望通知数:行数不足说明存在其他发送流程已持有的通知,
     * 本条消息禁止发送,并把本次已领到的通知释放回可重发状态,避免通知停留在SENDING。
     *
     * @param noticeIds  待领取通知ID列表
     * @param claimToken 本次领取标识
     * @return 领取成功返回true;行数不足返回false且不调用Bot
     */
    public boolean claim(List<Long> noticeIds, String claimToken) {
        if (CollectionUtils.isEmpty(noticeIds)) {
            return false;
        }
        int claimed = noticeAuditDao.claimByIds(noticeIds, claimToken);
        if (claimed != noticeIds.size()) {
            log.warn("股票通知发送-领取行数不足,禁止调用Bot: claimed={}, expected={}", claimed, noticeIds.size());
            releaseClaim(claimToken);
            return false;
        }
        return true;
    }

    /**
     * 以换仓关联标识为单位原子领取α换仓关联组仍可发送的腿。
     * <p>
     * α换仓两腿必须以关联组为单位领取,不能只领取一条腿;领取行数不等于预计腿数时释放本次领取并
     * 禁止调用Bot,已SENT腿不会被重新领取。
     *
     * @param rebalanceAssociationId 换仓关联标识
     * @param expectedLegCount       本次预计可发送的腿数
     * @param claimToken             本次领取标识
     * @return 领取成功返回true;行数不足返回false且不调用Bot
     */
    public boolean claimRebalanceGroup(String rebalanceAssociationId, int expectedLegCount, String claimToken) {
        if (rebalanceAssociationId == null || rebalanceAssociationId.isBlank() || expectedLegCount <= 0) {
            return false;
        }
        int claimed = noticeAuditDao.claimByRebalanceAssociationId(rebalanceAssociationId, claimToken);
        if (claimed != expectedLegCount) {
            log.warn("股票通知发送-α换仓关联组领取行数不足,禁止调用Bot: associationId={}, claimed={}, expected={}",
                    rebalanceAssociationId, claimed, expectedLegCount);
            releaseClaim(claimToken);
            return false;
        }
        return true;
    }

    /**
     * 释放本次领取:把本领取标识持有且仍未回写的SENDING通知退回可重发/最终失败状态。
     * <p>
     * 领取不完整(并发流程已持有部分通知)时调用,保证未调用Bot的通知不会停留在SENDING等待租约,
     * 且不静默丢弃;已回写终态的通知不受影响。
     *
     * @param claimToken 本次领取标识
     */
    public void releaseClaim(String claimToken) {
        if (claimToken == null || claimToken.isBlank()) {
            return;
        }
        try {
            int released = noticeAuditDao.releaseClaim(claimToken);
            if (released > 0) {
                log.warn("股票通知发送-已释放未发送的领取: claimToken={}, released={}", claimToken, released);
            }
        } catch (Exception e) {
            log.error("股票通知发送-释放领取异常,等待领取租约超时恢复: claimToken={}", claimToken, e);
        }
    }

    /**
     * 在发送前逐条冻结最终消息载荷。
     * <p>
     * 对每条通知读取创建时业务payload,合并最终{@code messageText}与{@code frozenAt},
     * 保留全部业务字段(如formalReason/originalExitReason/recoveryBar等),不得覆盖。
     * 最终payload经 {@link StockNoticePayloadCanonicalizer} 规范化后计算哈希。
     * 冻结UPDATE行数必须等于通知数,否则返回false并由调用方停止发送本条消息,禁止发送不可审计消息;
     * 自动重发必须复用首次冻结的文本与哈希,不得重新组合正文。
     *
     * @param noticeById  通知ID索引
     * @param noticeIds   待冻结的通知ID列表
     * @param messageText 最终消息文本
     * @param frozenAt    首次冻结时间(自动重发复用已冻结腿的时间)
     * @param claimToken  本次领取标识
     * @return 冻结成功(更新行数等于通知数)返回true;否则false
     */
    public boolean freezePayload(Map<Long, TornStockNoticeAuditDO> noticeById,
                                 List<Long> noticeIds,
                                 String messageText,
                                 LocalDateTime frozenAt,
                                 String claimToken) {
        if (CollectionUtils.isEmpty(noticeIds)) {
            return true;
        }
        List<NoticePayloadFinalizeCommand> commands = new ArrayList<>(noticeIds.size());
        for (Long noticeId : noticeIds) {
            TornStockNoticeAuditDO notice = noticeById.get(noticeId);
            if (notice == null) {
                log.error("股票通知发送-通知不存在,无法冻结payload: noticeId={}", noticeId);
                return false;
            }
            String finalPayload = StockNoticePayloadCanonicalizer.mergeAndCanonicalize(
                    notice.getPayloadSnapshot(), messageText, frozenAt);
            commands.add(new NoticePayloadFinalizeCommand(
                    noticeId, finalPayload, StockNoticePayloadCanonicalizer.sha256(finalPayload), claimToken));
        }
        int updated = noticeAuditDao.finalizePayload(commands);
        if (updated != noticeIds.size()) {
            log.error("股票通知发送-最终payload冻结行数不符: updated={}, expected={}", updated, noticeIds.size());
            return false;
        }
        return true;
    }

    /**
     * 将本次领取成功的通知标记为已发送(SENT)并设置发送成功时间。
     * <p>
     * SENT为终态且不得再次发送。回写必须同时匹配SENDING状态与领取标识:更新行数不足说明存在
     * 状态不一致(如领取已被租约恢复或另一流程持有),必须记录ERROR以便审计发现,
     * 不得把单腿成功解释为完整换仓通知成功(发送已完成,不回滚交易事实)。
     *
     * @param noticeIds  本条消息对应的通知ID列表
     * @param claimToken 本次领取标识
     */
    public void markSent(List<Long> noticeIds, String claimToken) {
        if (CollectionUtils.isEmpty(noticeIds)) {
            return;
        }
        try {
            int updated = noticeAuditDao.markSentByIds(noticeIds, claimToken);
            if (updated != noticeIds.size()) {
                log.error("股票通知发送-标记SENT行数不完整,同组通知状态不一致: updated={}, expected={}",
                        updated, noticeIds.size());
            }
        } catch (Exception e) {
            log.error("股票通知发送-批量标记SENT状态异常,通知停留SENDING等待租约恢复, noticeCount={}",
                    noticeIds.size(), e);
        }
    }

    /**
     * 将本次领取成功的通知标记为发送失败并记录实际错误信息。
     * <p>
     * 未达3次总尝试上限写入FAILED_RETRYABLE,由后续调度自动重发;达到上限写入FAILED_FINAL终态,
     * 不再自动发送。回写必须同时匹配SENDING状态与领取标识。回写异常时通知停留SENDING,
     * 由领取租约超时按"结果未知"恢复为可重发状态,不等待人工审核。
     *
     * @param noticeIds     本条消息对应的通知ID列表
     * @param claimToken    本次领取标识
     * @param failureReason 实际发送失败原因
     */
    public void markSendFailed(List<Long> noticeIds, String claimToken, String failureReason) {
        if (CollectionUtils.isEmpty(noticeIds)) {
            return;
        }
        try {
            int updated = noticeAuditDao.markSendFailedByIds(noticeIds, claimToken,
                    failureReason == null || failureReason.isBlank()
                            ? StockNoticeBotSender.NULL_RESPONSE_FAILURE_MESSAGE : failureReason);
            if (updated != noticeIds.size()) {
                log.error("股票通知发送-标记失败行数不完整,同组通知状态不一致: updated={}, expected={}",
                        updated, noticeIds.size());
            }
        } catch (Exception e) {
            log.error("股票通知发送-批量标记失败状态异常,通知停留SENDING等待租约恢复, noticeCount={}",
                    noticeIds.size(), e);
        }
    }

    /**
     * 将不可自动重发的通知置为人工核验终态(FAILED_FINAL)。
     * <p>
     * 用于关联批次不存在、α换仓关联组缺腿/重复腿/字段冲突等不可解释状态:不调用Bot,
     * 也不进入自动重发,只保留明确错误信息供人工核验。
     *
     * @param noticeIds    通知ID列表
     * @param errorMessage 人工核验原因
     */
    public void markFinal(List<Long> noticeIds, String errorMessage) {
        if (CollectionUtils.isEmpty(noticeIds)) {
            return;
        }
        try {
            int updated = noticeAuditDao.markFinalByIds(noticeIds, errorMessage);
            if (updated != noticeIds.size()) {
                log.error("股票通知发送-标记最终失败行数不完整,同组通知状态不一致: updated={}, expected={}",
                        updated, noticeIds.size());
            }
        } catch (Exception e) {
            log.error("股票通知发送-批量标记最终失败状态异常, noticeCount={}", noticeIds.size(), e);
        }
    }
}
