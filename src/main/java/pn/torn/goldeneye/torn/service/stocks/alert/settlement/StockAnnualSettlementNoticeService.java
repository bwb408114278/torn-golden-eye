package pn.torn.goldeneye.torn.service.stocks.alert.settlement;

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
import pn.torn.goldeneye.torn.service.stocks.alert.notice.*;
import pn.torn.goldeneye.utils.JsonUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 年度报告通知服务 - 负责年度报告PENDING通知审计、canonical payload与发送状态迁移
 * <p>
 * 与每日摘要同一自包含通知范式,复用既有审计、领取、冻结与自动重发链,不建设第二套消息平台:
 * <ol>
 *   <li>构建ANNUAL_SETTLEMENT类型的通知审计DO,填充被结算年最后一日、VIP群组ID、完整业务载荷与PENDING状态,
 *       通知编号格式为 "A" + 毫秒时间戳(与摘要前缀D区分),时间戳实现唯一收敛在{@link StockNoticeNoGenerator}</li>
 *   <li>以{@code (summary_date, notice_type)}为幂等键:重跑时先回读既有通知行复用其ID与冻结正文,
 *       不重复建行、不重新渲染,避免唯一索引冲突中断年度结算事务</li>
 *   <li>经 {@link StockNoticeSendService#sendSingleMessageResult} 发送,发送前以
 *       {@link StockNoticeSendRecorder} 完成数据库级领取与payload冻结;年度报告投递不受
 *       {@code VIP_STOCK_FORMAL_NOTICE_ENABLED} 约束,残留PENDING由既有发送链兜底</li>
 * </ol>
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAnnualSettlementNoticeService {
    /**
     * 发送前payload冻结失败时的统一失败原因
     */
    private static final String FREEZE_FAILURE_MESSAGE = "最终payload冻结行数不符";
    /**
     * 通知编号前缀(与每日摘要的D区分)
     */
    private static final String NOTICE_NO_PREFIX = "A";
    private final TornStockNoticeAuditDAO noticeAuditDAO;
    private final StockNoticeSendRecorder noticeSendRecorder;
    private final StockNoticeSendService noticeSendService;
    private final StockMarketClock marketClock;
    private final ProjectProperty projectProperty;

    /**
     * 保存或复用PENDING状态的年度报告通知审计记录。
     * <p>
     * 同一被结算年度只允许一条年度报告审计行:调用方在结算事务内先回读,已存在则直接复用,
     * 不存在才建行,保证重复执行不产生第二条通知,也不覆盖首次固化的正文。
     *
     * @param payload     年度报告业务载荷
     * @param reportText  年度报告正文
     * @param businessNow 本次结算的业务时间(通知编号时间戳取值)
     * @return 通知审计DO(含主键ID)
     */
    public TornStockNoticeAuditDO saveOrReusePendingNotice(AnnualSettlementNoticePayload payload, String reportText,
                                                           LocalDateTime businessNow) {
        TornStockNoticeAuditDO existing = noticeAuditDAO.selectBySummaryDateAndType(
                payload.summaryDate(), StockNoticeTypeEnum.ANNUAL_SETTLEMENT.getCode());
        if (existing != null) {
            log.info("VIP股票年度报告-复用既有通知审计行, noticeNo={}, noticeId={}",
                    existing.getNoticeNo(), existing.getId());
            return existing;
        }
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setNoticeNo(StockNoticeNoGenerator.generate(businessNow, NOTICE_NO_PREFIX, ""));
        notice.setNoticeType(StockNoticeTypeEnum.ANNUAL_SETTLEMENT.getCode());
        notice.setSummaryDate(payload.summaryDate());
        notice.setGroupId(projectProperty.getVipGroupId());
        notice.setSendStatus(StockNoticeStatusEnum.PENDING.getCode());
        notice.setSendAttemptCount(0);
        notice.setMessageRuleVersion(VipStockAlertScheduler.MESSAGE_RULE_VERSION);
        notice.setPayloadSnapshot(buildPayloadSnapshot(payload, reportText));
        notice.setPayloadHash(StockNoticePayloadCanonicalizer.sha256(notice.getPayloadSnapshot()));
        noticeAuditDAO.save(notice);
        return notice;
    }

    /**
     * 领取并发送年度报告至VIP群,并按发送结果回写通知审计终态。
     * <p>
     * 发送必须在结算事务提交后执行:先以数据库级领取(置为SENDING并累计一次尝试)为唯一发送入口,
     * 领取失败说明该通知已被其他发送流程持有或已终结,直接跳过;领取成功后冻结最终payload再调用Bot。
     * 已冻结(崩溃恢复或复用既有行)时复用冻结正文,不重新渲染。
     *
     * @param notice     通知审计DO(须已保存并具备主键)
     * @param reportText 本次渲染的年度报告正文
     */
    public void sendAndUpdateNotice(TornStockNoticeAuditDO notice, String reportText) {
        if (notice == null || notice.getId() == null) {
            log.error("VIP股票年度报告-通知未保存,无法领取发送: noticeNo={}",
                    notice == null ? null : notice.getNoticeNo());
            return;
        }
        String messageText = resolveMessageText(notice, reportText);
        if (messageText == null) {
            log.error("VIP股票年度报告-通知既未冻结又缺少可发送正文,跳过发送: noticeNo={}", notice.getNoticeNo());
            return;
        }
        Long noticeId = notice.getId();
        List<Long> noticeIds = List.of(noticeId);
        // 同一次发送编排只读取一次业务时间:领取、冻结与终态回写全部复用同一businessNow。
        LocalDateTime businessNow = marketClock.now();
        String claimToken = noticeSendRecorder.newClaimToken();
        if (!noticeSendRecorder.claim(noticeIds, claimToken, businessNow)) {
            log.warn("VIP股票年度报告-通知未被领取,已有发送流程持有或已终结,跳过: noticeNo={}", notice.getNoticeNo());
            return;
        }
        if (!noticeSendRecorder.freezePayload(Map.of(noticeId, notice), noticeIds, messageText,
                businessNow, claimToken, businessNow)) {
            log.error("VIP股票年度报告-最终payload冻结行数不符,停止发送: noticeNo={}", notice.getNoticeNo());
            noticeSendRecorder.markSendFailed(noticeIds, claimToken, FREEZE_FAILURE_MESSAGE, businessNow);
            return;
        }
        StockNoticeBotSender.SendResult sendResult = noticeSendService.sendSingleMessageResult(messageText);
        if (sendResult.success()) {
            noticeSendRecorder.markSent(noticeIds, claimToken, businessNow);
            log.info("VIP股票年度报告-发送成功, noticeNo={}", notice.getNoticeNo());
        } else {
            noticeSendRecorder.markSendFailed(noticeIds, claimToken, sendResult.failureReason(), businessNow);
            log.warn("VIP股票年度报告-发送失败, noticeNo={}, reason={}",
                    notice.getNoticeNo(), sendResult.failureReason());
        }
    }

    /**
     * 解析本次实际投递的正文:已冻结行复用冻结文本,未冻结行使用本次渲染文本。
     *
     * @param notice     通知审计DO
     * @param reportText 本次渲染的年度报告正文
     * @return 投递正文;两者皆空时返回null
     */
    private String resolveMessageText(TornStockNoticeAuditDO notice, String reportText) {
        if (StockNoticePayloadReader.isAlreadyFrozen(notice)) {
            return StockNoticePayloadReader.readFrozenMessageText(notice.getPayloadSnapshot());
        }
        String frozenText = StockNoticePayloadReader.readMessageText(notice);
        return frozenText != null ? frozenText : reportText;
    }

    /**
     * 生成载荷快照JSON。
     *
     * @param payload    年度报告业务载荷
     * @param reportText 年度报告正文
     * @return 载荷快照JSON文本
     */
    private String buildPayloadSnapshot(AnnualSettlementNoticePayload payload, String reportText) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("noticeType", StockNoticeTypeEnum.ANNUAL_SETTLEMENT.getCode());
        snapshot.put("settleYear", payload.settleYear());
        snapshot.put("boundaryTime", payload.boundaryTime() == null ? null : payload.boundaryTime().toString());
        snapshot.put("boundaryBarStartTime",
                payload.boundaryBarStartTime() == null ? null : payload.boundaryBarStartTime().toString());
        snapshot.put("boundaryBarDigest", payload.boundaryBarDigest());
        snapshot.put("summaryDate", payload.summaryDate().toString());
        snapshot.put("groupId", projectProperty.getVipGroupId());
        snapshot.put("settlementId", payload.settlementId());
        snapshot.put("ruleVersion", payload.ruleVersion());
        snapshot.put("initialCash", payload.initialCash());
        snapshot.put("openingEquity", payload.openingEquity());
        snapshot.put("closingCash", payload.closingCash());
        snapshot.put("closingReserved", payload.closingReserved());
        snapshot.put("closingMarketValue", payload.closingMarketValue());
        snapshot.put("closingEquity", payload.closingEquity());
        snapshot.put("cumulativeExtractedBefore", payload.cumulativeExtractedBefore());
        snapshot.put("extractedAmount", payload.extractedAmount());
        snapshot.put("cumulativeExtractedAfter", payload.cumulativeExtractedAfter());
        snapshot.put("yearReturn", payload.yearReturn());
        snapshot.put("coverageDays", payload.coverageDays());
        snapshot.put("partialYear", payload.partialYear());
        snapshot.put("annualizedReturn", payload.annualizedReturn());
        snapshot.put("openPositionStocks", payload.openPositionStocks());
        snapshot.put("messageText", reportText);
        return JsonUtils.objToJson(snapshot);
    }

    /**
     * 年度报告业务载荷(不含通知类型、群组与正文等通知链字段)。
     *
     * @param settleYear                被结算的自然年
     * @param boundaryTime              年度边界时点
     * @param boundaryBarStartTime      边界行情桶起点
     * @param boundaryBarDigest         边界行情证明摘要
     * @param summaryDate               摘要日期(被结算年最后一日)
     * @param settlementId              年度结算台账行ID
     * @param ruleVersion               结算口径版本
     * @param initialCash               初始资金快照
     * @param openingEquity             本年度基准
     * @param closingCash               边界可用现金
     * @param closingReserved           边界预留资金
     * @param closingMarketValue        边界持仓可变现市值
     * @param closingEquity             年末边界权益
     * @param cumulativeExtractedBefore 结算前累计已提取
     * @param extractedAmount           本年账面利润
     * @param cumulativeExtractedAfter  累计账面利润
     * @param yearReturn                区间收益率
     * @param coverageDays              区间自然日数
     * @param partialYear               是否不完整年度(试运行)
     * @param annualizedReturn          年化折算(不适用时为null)
     * @param openPositionStocks        边界开放持仓股票简称列表
     */
    public record AnnualSettlementNoticePayload(
            int settleYear,
            LocalDateTime boundaryTime,
            LocalDateTime boundaryBarStartTime,
            String boundaryBarDigest,
            LocalDate summaryDate,
            Long settlementId,
            String ruleVersion,
            BigDecimal initialCash,
            BigDecimal openingEquity,
            BigDecimal closingCash,
            BigDecimal closingReserved,
            BigDecimal closingMarketValue,
            BigDecimal closingEquity,
            BigDecimal cumulativeExtractedBefore,
            BigDecimal extractedAmount,
            BigDecimal cumulativeExtractedAfter,
            BigDecimal yearReturn,
            int coverageDays,
            boolean partialYear,
            BigDecimal annualizedReturn,
            List<String> openPositionStocks) {
    }
}
