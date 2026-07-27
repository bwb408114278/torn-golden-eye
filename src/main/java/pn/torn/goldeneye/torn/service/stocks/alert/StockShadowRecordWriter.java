package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService.EligibilityResult;
import pn.torn.goldeneye.torn.service.stocks.alert.StockShadowService.StockSignalEventContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;
import pn.torn.goldeneye.utils.JsonUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票影子记录写入器 - 步骤8-9:写入原始信号事件、影子批次、拒绝观察批次与通知审计
 * <p>
 * 从 {@link StockRoundTransactionService} 提取的影子记录与通知审计写入逻辑,职责单一:
 * <ul>
 *   <li>步骤8: 对每个边沿触发的信号评估写入原始信号事件,并根据决策创建无限资金
 *       影子批次或拒绝观察批次</li>
 *   <li>步骤9: 为已成交的买入/卖出批次写入PENDING状态的通知审计记录</li>
 * </ul>
 *
 * <h3>组合决策编码</h3>
 * <ul>
 *   <li>{@value #DECISION_FORMAL} - ALLOWED且已入选正式组合</li>
 *   <li>{@value #DECISION_SHADOW} - ALLOWED但未入选正式组合(无槽位或资金不足)</li>
 *   <li>{@value #DECISION_REJECTED} - REJECTED或OBSERVED</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockShadowRecordWriter {

    /**
     * 通知编号前缀
     */
    private static final String NOTICE_NO_PREFIX = "N";
    /**
     * 通知编号时间戳格式
     */
    private static final String NOTICE_NO_TIMESTAMP_PATTERN = "yyyyMMddHHmmssSSS";
    /**
     * 组合决策编码-正式建立
     */
    private static final String DECISION_FORMAL = "FORMAL";
    /**
     * 组合决策编码-影子建立
     */
    private static final String DECISION_SHADOW = "SHADOW";
    /**
     * 组合决策编码-拒绝建立
     */
    private static final String DECISION_REJECTED = "REJECTED";
    /**
     * 未知拒绝原因默认编码
     */
    private static final String UNKNOWN_REJECT_REASON = "UNKNOWN";
    /**
     * 空JSON对象文本
     */
    private static final String EMPTY_JSON = "{}";
    /**
     * 通知编号batchId后缀取模基数
     */
    private static final int BATCH_ID_MODULUS = 1000000;
    /**
     * 通知编号格式化器
     */
    private static final DateTimeFormatter NOTICE_NO_FORMATTER =
            DateTimeFormatter.ofPattern(NOTICE_NO_TIMESTAMP_PATTERN);

    private final StockShadowService shadowService;
    private final TornStockNoticeAuditDAO noticeAuditDAO;

    // ==================== 步骤8: 写入影子记录 ====================

    /**
     * 为全部信号评估结果写入原始信号事件、影子批次和拒绝观察批次。
     * <p>
     * 对每个边沿触发的信号评估:
     * <ul>
     *   <li>记录原始信号事件(recordSignalEvent)</li>
     *   <li>ALLOWED且未入选正式 -&gt; 创建无限资金影子批次</li>
     *   <li>REJECTED/OBSERVED -&gt; 创建拒绝观察批次</li>
     * </ul>
     *
     * @param allEvaluations 全部信号评估结果
     * @param roundTime      本轮时间
     */
    public void writeShadowRecords(List<? extends SignalEvaluationView> allEvaluations, LocalDateTime roundTime) {
        for (SignalEvaluationView evaluation : allEvaluations) {
            if (!evaluation.edgeTriggered() || evaluation.primaryStrategy() == null) {
                continue;
            }
            writeSingleShadowRecord(evaluation, roundTime);
        }
    }

    /**
     * 写入单个边沿触发信号的影子记录。
     * <p>
     * 组装信号事件上下文并记录事件,然后根据组合决策创建对应的影子批次或拒绝观察批次。
     *
     * @param evaluation 信号评估结果
     * @param roundTime  本轮时间
     */
    private void writeSingleShadowRecord(SignalEvaluationView evaluation, LocalDateTime roundTime) {
        EligibilityResult eligibility = evaluation.eligibilityResult();
        String eligibilityResultCode = eligibility != null ? eligibility.result().getCode() : null;
        List<String> eligibilityReasons = eligibility != null ? eligibility.reasons() : List.of();
        String portfolioDecision = determinePortfolioDecision(evaluation, eligibility);
        String rejectReason = determineRejectReason(eligibility);

        StockSignalEventContext context = new StockSignalEventContext(
                evaluation.stocksId(),
                evaluation.stocksShortname(),
                evaluation.primaryStrategy().getStrategyType().getCode(),
                StockRoundTransactionService.BUY_RULE_VERSION,
                evaluation.qualityScore(),
                buildFeatureSnapshot(evaluation.context()),
                buildStyleSnapshot(evaluation.monthlyState()),
                eligibilityResultCode,
                eligibilityReasons,
                evaluation.candidateRank(),
                portfolioDecision,
                rejectReason,
                roundTime
        );

        TornStockSignalEventDO event = shadowService.recordSignalEvent(context);

        if (DECISION_SHADOW.equals(portfolioDecision)) {
            shadowService.createUnlimitedShadowBatch(event);
        } else if (DECISION_REJECTED.equals(portfolioDecision)) {
            shadowService.createRejectedObservationBatch(event, rejectReason);
        }
    }

    /**
     * 判定组合决策编码。
     * <p>
     * ALLOWED且已入选正式 -&gt; FORMAL;ALLOWED但未入选(无槽位/资金不足) -&gt; SHADOW;
     * REJECTED/OBSERVED -&gt; REJECTED。
     *
     * @param evaluation  信号评估
     * @param eligibility 资格结果
     * @return 组合决策编码
     */
    private String determinePortfolioDecision(SignalEvaluationView evaluation, EligibilityResult eligibility) {
        if (eligibility == null || StockEligibilityResultEnum.ALLOWED != eligibility.result()) {
            return DECISION_REJECTED;
        }
        if (!evaluation.acceptedFormal()) {
            return DECISION_SHADOW;
        }
        return DECISION_FORMAL;
    }

    /**
     * 判定拒绝原因编码。
     * <p>
     * 非拒绝时返回null;拒绝但无原因时返回{@value #UNKNOWN_REJECT_REASON};
     * 否则返回原因列表的首个编码。
     *
     * @param eligibility 资格结果
     * @return 拒绝原因编码;非拒绝时返回null
     */
    private String determineRejectReason(EligibilityResult eligibility) {
        if (eligibility == null || StockEligibilityResultEnum.ALLOWED == eligibility.result()) {
            return null;
        }
        List<String> reasons = eligibility.reasons();
        if (reasons == null || reasons.isEmpty()) {
            return UNKNOWN_REJECT_REASON;
        }
        return reasons.getFirst();
    }

    /**
     * 构建特征快照JSON。
     *
     * @param context 买入上下文
     * @return 特征快照JSON文本;上下文为null时返回{@value #EMPTY_JSON}
     */
    private String buildFeatureSnapshot(BuyContext context) {
        if (context == null) {
            return EMPTY_JSON;
        }
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("referencePrice", context.referencePrice());
        snapshot.put("ma1d", context.ma1d());
        snapshot.put("ma7d", context.ma7d());
        snapshot.put("ma30d", context.ma30d());
        snapshot.put("zscore1d", context.zscore1d());
        snapshot.put("zscore7d", context.zscore7d());
        snapshot.put("zscore30d", context.zscore30d());
        snapshot.put("return6h", context.return6h());
        snapshot.put("return1d", context.return1d());
        snapshot.put("return7d", context.return7d());
        snapshot.put("return14d", context.return14d());
        snapshot.put("low30d", context.low30d());
        snapshot.put("high30d", context.high30d());
        snapshot.put("width30d", context.width30d());
        snapshot.put("position30", context.position30());
        snapshot.put("pctAbove30dLow", context.pctAbove30dLow());
        snapshot.put("pctBelow30dHigh", context.pctBelow30dHigh());
        return JsonUtils.objToJson(snapshot);
    }

    /**
     * 构建风格快照JSON。
     *
     * @param monthlyState 月度状态
     * @return 风格快照JSON文本;月度状态为null时返回{@value #EMPTY_JSON}
     */
    private String buildStyleSnapshot(TornStockMonthlyStateDO monthlyState) {
        if (monthlyState == null) {
            return EMPTY_JSON;
        }
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("strategyFitPrior", monthlyState.getStrategyFitPrior());
        snapshot.put("maturity", monthlyState.getMaturity());
        snapshot.put("riskLevel", monthlyState.getRiskLevel());
        snapshot.put("effectiveMonth", monthlyState.getEffectiveMonth());
        return JsonUtils.objToJson(snapshot);
    }

    // ==================== 步骤9: 写入通知审计 ====================

    /**
     * 为已成交的买入/卖出写入PENDING通知审计。
     * <p>
     * 对每个已成交的买入批次创建BUY类型PENDING通知;
     * 对每个已成交的卖出批次创建SELL类型PENDING通知。
     *
     * @param entryFilledBatches 已成交买入批次
     * @param exitFilledBatches  已成交卖出批次
     * @param roundTime          本轮时间
     */
    public void writeNoticeAudits(List<TornStockVirtualBatchDO> entryFilledBatches,
                                  List<TornStockVirtualBatchDO> exitFilledBatches,
                                  LocalDateTime roundTime) {
        List<TornStockNoticeAuditDO> notices = new ArrayList<>();
        collectBuyNotices(entryFilledBatches, roundTime, notices);
        collectSellNotices(exitFilledBatches, roundTime, notices);

        if (!notices.isEmpty()) {
            noticeAuditDAO.saveBatch(notices);
            log.info("通知审计写入完成: buyNotices={}, sellNotices={}",
                    entryFilledBatches.size(), exitFilledBatches.size());
        }
    }

    /**
     * 为已成交买入批次构建通知审计并追加到列表。
     *
     * @param batches   已成交买入批次
     * @param roundTime 本轮时间
     * @param notices   通知审计输出列表
     */
    private void collectBuyNotices(List<TornStockVirtualBatchDO> batches,
                                   LocalDateTime roundTime,
                                   List<TornStockNoticeAuditDO> notices) {
        for (TornStockVirtualBatchDO batch : batches) {
            notices.add(buildNoticeAudit(batch, StockNoticeTypeEnum.BUY, roundTime));
        }
    }

    /**
     * 为已成交卖出批次构建通知审计并追加到列表。
     *
     * @param batches   已成交卖出批次
     * @param roundTime 本轮时间
     * @param notices   通知审计输出列表
     */
    private void collectSellNotices(List<TornStockVirtualBatchDO> batches,
                                    LocalDateTime roundTime,
                                    List<TornStockNoticeAuditDO> notices) {
        for (TornStockVirtualBatchDO batch : batches) {
            notices.add(buildNoticeAudit(batch, StockNoticeTypeEnum.SELL, roundTime));
        }
    }

    /**
     * 构建通知审计DO(PENDING状态)。
     *
     * @param batch      关联批次
     * @param noticeType 通知类型
     * @param roundTime  本轮时间
     * @return 未保存的通知审计DO
     */
    private TornStockNoticeAuditDO buildNoticeAudit(TornStockVirtualBatchDO batch,
                                                    StockNoticeTypeEnum noticeType,
                                                    LocalDateTime roundTime) {
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setNoticeNo(generateNoticeNo(batch, noticeType));
        notice.setBatchId(batch.getId());
        notice.setNoticeType(noticeType.getCode());
        notice.setScheduledRoundTime(roundTime);
        notice.setSendStatus(StockNoticeStatusEnum.PENDING.getCode());
        notice.setSendAttemptCount(0);
        notice.setMessageRuleVersion(StockRoundTransactionService.MESSAGE_RULE_VERSION);
        notice.setPayloadHash(generatePayloadHash(batch, noticeType));
        notice.setPayloadSnapshot(buildNoticePayload(batch, noticeType));
        return notice;
    }

    /**
     * 生成通知编号。
     * <p>
     * 格式: "N" + yyyyMMddHHmmssSSS + batchId后6位 + noticeType首字符
     *
     * @param batch      关联批次
     * @param noticeType 通知类型
     * @return 通知编号
     */
    private String generateNoticeNo(TornStockVirtualBatchDO batch, StockNoticeTypeEnum noticeType) {
        String timestamp = LocalDateTime.now().format(NOTICE_NO_FORMATTER);
        String batchSuffix = batch.getId() != null
                ? String.valueOf(batch.getId() % BATCH_ID_MODULUS) : "0";
        return NOTICE_NO_PREFIX + timestamp + batchSuffix + noticeType.getCode().charAt(0);
    }

    /**
     * 生成通知载荷哈希(SHA-256:用batchId+noticeType拼接后计算)。
     *
     * @param batch      关联批次
     * @param noticeType 通知类型
     * @return 载荷哈希(64位十六进制字符串)
     */
    private String generatePayloadHash(TornStockVirtualBatchDO batch, StockNoticeTypeEnum noticeType) {
        return StockHashUtils.sha256(batch.getId() + "_" + noticeType.getCode());
    }

    /**
     * 构建通知载荷快照JSON。
     * <p>
     * BUY类型包含入场参考价、股数、投入资金和槽位编号;
     * SELL类型包含卖出参考价、净收益、卖出收入和退出原因。
     *
     * @param batch      关联批次
     * @param noticeType 通知类型
     * @return 载荷快照JSON文本
     */
    private String buildNoticePayload(TornStockVirtualBatchDO batch, StockNoticeTypeEnum noticeType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("noticeType", noticeType.getCode());
        payload.put("batchNo", batch.getBatchNo());
        payload.put("stocksId", batch.getStocksId());
        payload.put("stocksShortname", batch.getStocksShortname());
        payload.put("primaryStrategy", batch.getPrimaryStrategy());
        if (StockNoticeTypeEnum.BUY == noticeType) {
            payload.put("entryReferencePrice", batch.getEntryReferencePrice());
            payload.put("quantity", batch.getQuantity());
            payload.put("investedCash", batch.getInvestedCash());
            payload.put("slotNo", batch.getSlotNo());
        } else {
            payload.put("exitReferencePrice", batch.getExitReferencePrice());
            payload.put("netReturn", batch.getNetReturn());
            payload.put("sellProceeds", batch.getSellProceeds());
            payload.put("exitReason", batch.getExitReason());
        }
        return JsonUtils.objToJson(payload);
    }

    // ==================== 信号评估视图接口 ====================

    /**
     * 信号评估结果接口 - 供影子记录写入器消费。
     * <p>
     * 从 {@link StockRoundTransactionService} 的内部 SignalEvaluation 抽象出的只读视图,
     * 使写入器不直接依赖事务服务的内部类,降低耦合。当后续提取
     * StockBuySignalEvaluator.SignalEvaluation record后,可直接实现本接口。
     *
     * @author Bai
     * @version 1.2.12
     * @since 2026.07.25
     */
    public interface SignalEvaluationView {

        /**
         * 股票ID。
         *
         * @return 股票ID
         */
        Integer stocksId();

        /**
         * 股票简称。
         *
         * @return 股票简称
         */
        String stocksShortname();

        /**
         * 主策略(质量分最高的命中策略)。
         *
         * @return 主策略;无命中时为null
         */
        StockBuyStrategy primaryStrategy();

        /**
         * 全部命中策略列表。
         *
         * @return 命中策略列表
         */
        List<StockBuyStrategy> matchedStrategies();

        /**
         * 主策略质量分。
         *
         * @return 质量分
         */
        BigDecimal qualityScore();

        /**
         * 本轮是否命中任何策略。
         *
         * @return 命中时返回true
         */
        boolean currentMatches();

        /**
         * 是否为false-&gt;true边沿触发。
         *
         * @return 边沿触发时返回true
         */
        boolean edgeTriggered();

        /**
         * 买入上下文。
         *
         * @return 买入上下文
         */
        BuyContext context();

        /**
         * 信号状态记录。
         *
         * @return 信号状态记录
         */
        TornStockSignalStateDO signalState();

        /**
         * 月度状态记录。
         *
         * @return 月度状态记录
         */
        TornStockMonthlyStateDO monthlyState();

        /**
         * 资格评估结果。
         *
         * @return 资格评估结果;未执行资格检查时为null
         */
        EligibilityResult eligibilityResult();

        /**
         * 候选排名。
         *
         * @return 候选排名;未入选正式时为null
         */
        Integer candidateRank();

        /**
         * 是否已被正式组合接纳。
         *
         * @return 已接纳时返回true
         */
        boolean acceptedFormal();
    }
}
