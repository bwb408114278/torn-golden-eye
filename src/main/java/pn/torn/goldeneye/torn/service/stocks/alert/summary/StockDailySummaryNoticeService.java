package pn.torn.goldeneye.torn.service.stocks.alert.summary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.market.round.VipStockAlertScheduler;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeBotSender;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticePayloadCanonicalizer;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendRecorder;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票日报通知服务 - 负责PENDING通知审计、canonical payload与发送状态迁移
 * <p>
 * 日报摘要文本在渲染完成后交由本服务落审计并发送:
 * <ol>
 *   <li>构建DAILY_SUMMARY类型的通知审计DO,填充摘要日期、VIP群组ID、载荷快照与PENDING状态,
 *       通知编号格式为 "D" + yyyyMMddHHmmssSSS + "S"(Summary首字符)</li>
 *   <li>载荷哈希基于完整摘要载荷快照的规范化JSON({@link StockNoticePayloadCanonicalizer#sha256})</li>
 *   <li>经 {@link StockNoticeSendService#sendSingleMessageResult} 发送(HTTP 2xx且body非空视为成功),
 *       发送前以 {@link StockNoticeSendRecorder} 完成数据库级领取与payload冻结</li>
 *   <li>发送成功回写SENT;发送失败回写FAILED_RETRYABLE并由后续调度自动重发,
 *       达到3次总尝试上限后为FAILED_FINAL;回写异常时通知停留SENDING由领取租约超时恢复,
 *       不等待人工审核</li>
 * </ol>
 * 时间一律使用注入的 {@link StockMarketClock},禁止使用真实墙钟。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.08.09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDailySummaryNoticeService {

    /**
     * 消息规则版本(与 {@link VipStockAlertScheduler#MESSAGE_RULE_VERSION} 保持一致)
     */
    private static final String MESSAGE_RULE_VERSION = "1.0.0";
    /**
     * 发送前payload冻结失败时的统一失败原因
     */
    private static final String FREEZE_FAILURE_MESSAGE = "最终payload冻结行数不符";
    /**
     * 通知编号前缀
     */
    private static final String NOTICE_NO_PREFIX = "D";
    /**
     * 通知编号时间戳格式
     */
    private static final String NOTICE_NO_TIMESTAMP_PATTERN = "yyyyMMddHHmmssSSS";
    /**
     * 通知编号格式化器
     */
    private static final DateTimeFormatter NOTICE_NO_FORMATTER =
            DateTimeFormatter.ofPattern(NOTICE_NO_TIMESTAMP_PATTERN);

    private final TornStockNoticeAuditDAO noticeAuditDAO;
    private final StockNoticeSendRecorder noticeSendRecorder;
    private final StockNoticeSendService noticeSendService;
    private final StockMarketClock marketClock;
    private final ProjectProperty projectProperty;

    /**
     * 保存PENDING状态的通知审计记录。
     *
     * @param summaryDate 摘要日期
     * @param summaryText 摘要文本
     * @return 已保存的通知审计DO(含主键ID)
     */
    public TornStockNoticeAuditDO savePendingNotice(LocalDate summaryDate, String summaryText) {
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setNoticeNo(generateNoticeNo());
        notice.setNoticeType(StockNoticeTypeEnum.DAILY_SUMMARY.getCode());
        notice.setSummaryDate(summaryDate);
        notice.setGroupId(projectProperty.getVipGroupId());
        notice.setSendStatus(StockNoticeStatusEnum.PENDING.getCode());
        notice.setSendAttemptCount(0);
        notice.setMessageRuleVersion(MESSAGE_RULE_VERSION);
        notice.setPayloadSnapshot(buildPayloadSnapshot(summaryDate, summaryText));
        notice.setPayloadHash(generatePayloadHash(notice.getPayloadSnapshot()));
        noticeAuditDAO.save(notice);
        return notice;
    }

    /**
     * 领取并发送摘要至VIP群,并按发送结果回写通知审计终态。
     * <p>
     * 与普通通知共用同一发送闭包:先以数据库级领取(置为SENDING并累计一次尝试)为唯一发送入口,
     * 领取失败说明该摘要已被其他发送流程持有,直接跳过;领取成功后冻结最终payload再调用Bot。
     * 发送成功回写SENT;失败回写FAILED_RETRYABLE由后续调度自动重发,达到3次总尝试上限后为
     * FAILED_FINAL;冻结或回写异常时通知停留SENDING,由领取租约超时按结果未知恢复,
     * 不等待人工审核,也不回滚任何交易事实。
     *
     * @param notice      通知审计DO(须已保存并具备主键)
     * @param summaryText 摘要文本
     */
    public void sendAndUpdateNotice(TornStockNoticeAuditDO notice, String summaryText) {
        if (notice == null || notice.getId() == null) {
            log.error("VIP股票每日摘要-通知未保存,无法领取发送: noticeNo={}",
                    notice == null ? null : notice.getNoticeNo());
            return;
        }
        Long noticeId = notice.getId();
        List<Long> noticeIds = List.of(noticeId);
        String claimToken = noticeSendRecorder.newClaimToken();
        if (!noticeSendRecorder.claim(noticeIds, claimToken)) {
            log.warn("VIP股票每日摘要-通知未被领取,已有发送流程持有,跳过: noticeNo={}", notice.getNoticeNo());
            return;
        }
        if (!noticeSendRecorder.freezePayload(Map.of(noticeId, notice), noticeIds, summaryText,
                marketClock.now(), claimToken)) {
            log.error("VIP股票每日摘要-最终payload冻结行数不符,停止发送: noticeNo={}", notice.getNoticeNo());
            noticeSendRecorder.markSendFailed(noticeIds, claimToken, FREEZE_FAILURE_MESSAGE);
            return;
        }
        StockNoticeBotSender.SendResult sendResult = noticeSendService.sendSingleMessageResult(summaryText);
        if (sendResult.success()) {
            noticeSendRecorder.markSent(noticeIds, claimToken);
            log.info("VIP股票每日摘要-发送成功, noticeNo={}", notice.getNoticeNo());
        } else {
            noticeSendRecorder.markSendFailed(noticeIds, claimToken, sendResult.failureReason());
            log.warn("VIP股票每日摘要-发送失败, noticeNo={}, reason={}",
                    notice.getNoticeNo(), sendResult.failureReason());
        }
    }

    /**
     * 生成通知编号。
     * <p>
     * 格式: "D" + yyyyMMddHHmmssSSS + "S"
     *
     * @return 通知编号
     */
    private String generateNoticeNo() {
        String timestamp = marketClock.now().format(NOTICE_NO_FORMATTER);
        return NOTICE_NO_PREFIX + timestamp + "S";
    }

    /**
     * 生成载荷哈希(SHA-256,基于完整摘要载荷快照的规范化JSON)。
     * <p>
     * 使用 {@link StockNoticePayloadCanonicalizer} 做确定性规范化后计算,与创建、发送合并、
     * 数据库复核共用同一canonicalizer,保证JSONB读回后哈希可复核。
     *
     * @param payloadSnapshot 完整载荷快照JSON
     * @return 载荷哈希
     */
    private String generatePayloadHash(String payloadSnapshot) {
        return StockNoticePayloadCanonicalizer.sha256(payloadSnapshot);
    }

    /**
     * 生成载荷快照JSON。
     *
     * @param summaryDate 摘要日期
     * @param summaryText 摘要文本
     * @return 载荷快照JSON文本
     */
    private String buildPayloadSnapshot(LocalDate summaryDate, String summaryText) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("noticeType", StockNoticeTypeEnum.DAILY_SUMMARY.getCode());
        payload.put("summaryDate", summaryDate.toString());
        payload.put("groupId", projectProperty.getVipGroupId());
        payload.put("messageText", summaryText);
        return JsonUtils.objToJson(payload);
    }
}
