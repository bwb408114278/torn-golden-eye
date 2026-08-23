package pn.torn.goldeneye.torn.service.stocks.alert.summary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticePayloadCanonicalizer;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.market.round.VipStockAlertScheduler;

/**
 * 股票日报通知服务 - 负责PENDING通知审计、canonical payload与发送状态迁移
 * <p>
 * 日报摘要文本在渲染完成后交由本服务落审计并发送:
 * <ol>
 *   <li>构建DAILY_SUMMARY类型的通知审计DO,填充摘要日期、VIP群组ID、载荷快照与PENDING状态,
 *       通知编号格式为 "D" + yyyyMMddHHmmssSSS + "S"(Summary首字符)</li>
 *   <li>载荷哈希基于完整摘要载荷快照的规范化JSON({@link StockNoticePayloadCanonicalizer#sha256})</li>
 *   <li>复用 {@link StockNoticeSendService#sendSingleMessage} 发送(HTTP 2xx且body非空视为成功),
 *       发送成功时更新sendStatus=SENT并记录sentAt;发送失败时更新sendStatus=FAILED并记录错误信息;
 *       发送异常不抛出,仅记录日志并标记FAILED,等待后续重试机制处理</li>
 * </ol>
 * 时间一律使用注入的 {@link StockMarketClock},禁止使用真实墙钟。
 *
 * @author Bai
 * @version 1.2.14
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
     * 调用统一发送服务发送摘要至VIP群,并根据发送结果更新通知审计状态。
     * <p>
     * 复用 {@link StockNoticeSendService#sendSingleMessage} 发送(HTTP 2xx且body非空视为成功),
     * 发送成功时更新sendStatus=SENT并记录sentAt;发送失败时更新sendStatus=FAILED并记录错误信息。
     * 发送异常时不抛出,仅记录日志并标记FAILED,等待后续重试机制处理。
     *
     * @param notice      通知审计DO
     * @param summaryText 摘要文本
     */
    public void sendAndUpdateNotice(TornStockNoticeAuditDO notice, String summaryText) {
        notice.setSendAttemptCount(notice.getSendAttemptCount() == null ? 1 : notice.getSendAttemptCount() + 1);
        notice.setAttemptedAt(marketClock.now());
        try {
            boolean sent = noticeSendService.sendSingleMessage(summaryText);
            if (sent) {
                notice.setSendStatus(StockNoticeStatusEnum.SENT.getCode());
                notice.setSentAt(marketClock.now());
                noticeAuditDAO.updateById(notice);
                log.info("VIP股票每日摘要-发送成功, noticeNo={}", notice.getNoticeNo());
            } else {
                notice.setSendStatus(StockNoticeStatusEnum.FAILED.getCode());
                notice.setErrorMessage("统一发送服务返回失败");
                noticeAuditDAO.updateById(notice);
                log.warn("VIP股票每日摘要-发送失败, noticeNo={}", notice.getNoticeNo());
            }
        } catch (Exception e) {
            log.error("VIP股票每日摘要-发送异常, noticeNo={}", notice.getNoticeNo(), e);
            notice.setSendStatus(StockNoticeStatusEnum.FAILED.getCode());
            notice.setErrorMessage(e.getMessage());
            noticeAuditDAO.updateById(notice);
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
