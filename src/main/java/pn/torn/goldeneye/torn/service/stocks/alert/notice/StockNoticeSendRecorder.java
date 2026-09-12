package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 股票通知发送结果记录器 - 发送前冻结最终payload并按发送结果回写单次发送终态。
 * <p>
 * 普通通知与α换仓关联组共用本记录器,保证"冻结行数必须完整"与"SENT/FAILED为终态且只尝试一次"
 * 的口径只有一套实现:冻结UPDATE行数不等于通知数时禁止调用Bot;标记终态UPDATE行数不足说明同组
 * 通知状态不一致,必须记录ERROR以便审计发现。标记状态异常不向上抛出,避免中断后续通知投递。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockNoticeSendRecorder {

    private final TornStockNoticeAuditDAO noticeAuditDao;

    /**
     * 在发送前逐条冻结最终消息载荷。
     * <p>
     * 对每条通知读取创建时业务payload,合并最终{@code messageText}与{@code frozenAt},
     * 保留全部业务字段(如formalReason/originalExitReason/recoveryBar等),不得覆盖。
     * 最终payload经 {@link StockNoticePayloadCanonicalizer} 规范化后计算哈希。
     * 冻结UPDATE行数必须等于通知数,否则返回false并由调用方停止发送本条消息,禁止发送不可审计消息。
     *
     * @param noticeById  通知ID索引
     * @param noticeIds   待冻结的通知ID列表
     * @param messageText 最终消息文本
     * @param attemptedAt 实际发送尝试时间
     * @return 冻结成功(更新行数等于通知数)返回true;否则false
     */
    public boolean freezePayload(Map<Long, TornStockNoticeAuditDO> noticeById,
                                 List<Long> noticeIds,
                                 String messageText,
                                 LocalDateTime attemptedAt) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return true;
        }
        List<NoticePayloadFinalizeCommand> commands = new ArrayList<>(noticeIds.size());
        for (Long noticeId : noticeIds) {
            TornStockNoticeAuditDO notice = noticeById.get(noticeId);
            if (notice == null) {
                log.error("股票通知发送-通知不存在,无法冻结payload: noticeId={}", noticeId);
                return false;
            }
            String originalPayload = notice.getPayloadSnapshot();
            String finalPayload = StockNoticePayloadCanonicalizer.mergeAndCanonicalize(
                    originalPayload, messageText, attemptedAt);
            commands.add(new NoticePayloadFinalizeCommand(
                    noticeId, finalPayload, StockNoticePayloadCanonicalizer.sha256(finalPayload), attemptedAt));
        }
        int updated = noticeAuditDao.finalizePayload(commands);
        if (updated != noticeIds.size()) {
            log.error("股票通知发送-最终payload冻结行数不符: updated={}, expected={}", updated, noticeIds.size());
            return false;
        }
        return true;
    }

    /**
     * 将一批通知标记为已发送(SENT)并设置发送成功时间。
     * <p>
     * SENT为终态且不得再次发送。更新行数必须完整等于本条消息的通知数:行数不足说明存在
     * 一条通知已处于其他终态而另一条仍为PENDING的部分成功状态,必须记录ERROR以便审计发现,
     * 不得把单腿成功解释为完整换仓通知成功(发送已完成,不回滚交易事实)。
     *
     * @param noticeIds 本条消息对应的通知ID列表
     */
    public void markSent(List<Long> noticeIds) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return;
        }
        try {
            int updated = noticeAuditDao.markSentByIds(noticeIds);
            if (updated != noticeIds.size()) {
                log.error("股票通知发送-标记SENT行数不完整,同组通知状态不一致: updated={}, expected={}",
                        updated, noticeIds.size());
            }
        } catch (Exception e) {
            log.error("股票通知发送-批量标记SENT状态异常, noticeCount={}", noticeIds.size(), e);
        }
    }

    /**
     * 将一批通知标记为发送失败(FAILED)并记录实际错误信息。
     * <p>
     * FAILED为本批次终态,不被后续普通调度自动重新查询和发送。更新行数必须完整等于
     * 本条消息的通知数:行数不足说明同组通知未全部进入同一失败终态,必须记录ERROR。
     *
     * @param noticeIds     本条消息对应的通知ID列表
     * @param failureReason 实际发送失败原因
     */
    public void markSendFailed(List<Long> noticeIds, String failureReason) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return;
        }
        try {
            int updated = noticeAuditDao.markSendFailedByIds(noticeIds,
                    failureReason == null || failureReason.isBlank()
                            ? StockNoticeBotSender.NULL_RESPONSE_FAILURE_MESSAGE : failureReason);
            if (updated != noticeIds.size()) {
                log.error("股票通知发送-标记FAILED行数不完整,同组通知状态不一致: updated={}, expected={}",
                        updated, noticeIds.size());
            }
        } catch (Exception e) {
            log.error("股票通知发送-批量标记FAILED状态异常, noticeCount={}", noticeIds.size(), e);
        }
    }
}
