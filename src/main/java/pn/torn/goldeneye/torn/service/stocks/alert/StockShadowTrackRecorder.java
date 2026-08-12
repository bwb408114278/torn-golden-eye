package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCandidateAllocationResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchSignalFields;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService.EligibilityResult;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;
import pn.torn.goldeneye.utils.JsonUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 股票影子轨道记录器 - 信号事件、无限资金影子批次与拒绝观察批次的唯一写入入口。
 * <p>
 * 收敛 P1-1 双写职责: 原始信号事件、无限资金影子批次与拒绝观察批次的全部写入
 * 只在本类发生,候选影子/正式批次写入由 {@link StockCandidateTrackAllocationService} 负责,
 * 通知审计写入由 {@link StockShadowRecordWriter} 负责。本类不包含任何通知审计逻辑。
 * <p>
 * 组合决策编码:
 * <ul>
 *   <li>{@value #DECISION_FORMAL} - ALLOWED且已入选正式组合</li>
 *   <li>{@value #DECISION_SHADOW} - ALLOWED但未入选正式组合(含入选候选影子槽位、无槽位或资金不足)</li>
 *   <li>{@value #DECISION_REJECTED} - REJECTED或OBSERVED</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockShadowTrackRecorder {

    /**
     * 事件编号前缀
     */
    private static final String EVENT_NO_PREFIX = "E";
    /**
     * 无限资金影子批次编号前缀
     */
    private static final String SHADOW_BATCH_NO_PREFIX = "S";
    /**
     * 拒绝观察批次编号前缀
     */
    private static final String REJECTED_BATCH_NO_PREFIX = "R";
    /**
     * 事件/批次编号时间戳格式(yyyyMMddHHmm)
     */
    private static final String NO_TIMESTAMP_PATTERN = "yyyyMMddHHmm";
    /**
     * 策略类型截取长度(取前3字符用于事件编号)
     */
    private static final int STRATEGY_TYPE_TRUNCATE_LENGTH = 3;
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
     * 编号时间戳格式化器
     */
    private static final DateTimeFormatter NO_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern(NO_TIMESTAMP_PATTERN);
    /**
     * 信号事件为空校验提示
     */
    private static final String MSG_SIGNAL_EVENT_NULL = "信号事件不能为空";
    /**
     * 信号事件主键ID为空校验提示
     */
    private static final String MSG_SIGNAL_EVENT_ID_NULL = "信号事件主键ID不能为空";

    private final TornStockSignalEventDAO signalEventDao;
    private final TornStockVirtualBatchDAO virtualBatchDao;


    // ==================== 原始信号事件 ====================

    /**
     * 记录原始信号事件并保存。
     * <p>
     * 创建并保存一次 false -&gt; true 信号事件的完整快照,生成业务唯一事件编号
     * (格式: "E" + 业务轮次yyyyMMddHHmm + stocksId + strategyType前3字符),
     * 写入特征快照、风格快照、资格结果与原因、候选排名与组合决策等字段。
     * 不发送即时群消息。
     *
     * @param context 信号事件上下文,包含创建事件所需的全部信息
     * @return 已保存的信号事件DO(含主键ID与事件编号)
     */
    public TornStockSignalEventDO recordSignalEvent(StockSignalEventContext context) {
        Objects.requireNonNull(context, "信号事件上下文不能为空");
        Objects.requireNonNull(context.stocksId(), "股票ID不能为空");
        Objects.requireNonNull(context.strategyType(), "策略类型不能为空");

        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setEventNo(generateEventNo(context.roundTime(), context.stocksId(), context.strategyType()));
        event.setRoundTime(context.roundTime());
        event.setStocksId(context.stocksId());
        event.setStocksShortname(context.stocksShortname());
        event.setStrategyType(context.strategyType());
        event.setSignalReferencePrice(context.signalReferencePrice());
        event.setStylePrior(context.stylePrior());
        event.setStyleMaturity(context.styleMaturity());
        event.setRiskLevel(context.riskLevel());
        event.setStyleEffectiveMonth(context.styleEffectiveMonth());
        event.setBuyRuleVersion(context.buyRuleVersion());
        event.setQualityScore(context.qualityScore());
        event.setFeatureSnapshot(context.featureSnapshot());
        event.setStyleSnapshot(context.styleSnapshot());
        event.setEligibilityResult(context.eligibilityResult());
        event.setEligibilityReasons(convertReasonsToJson(context.eligibilityReasons()));
        event.setCandidateRank(context.candidateRank());
        event.setPortfolioDecision(context.portfolioDecision());
        event.setRejectReason(context.rejectReason());
        event.setObservationDataIncomplete(false);

        signalEventDao.insertIgnoreConflict(event);
        TornStockSignalEventDO persisted = signalEventDao.selectByBusinessKeyForUpdate(
                context.stocksId(), context.strategyType(), context.roundTime(), context.buyRuleVersion());
        if (persisted == null || persisted.getId() == null) {
            throw new IllegalStateException("信号事件插入后无法读取业务唯一事件: stocksId="
                    + context.stocksId() + ", strategyType=" + context.strategyType()
                    + ", roundTime=" + context.roundTime());
        }
        log.info("信号事件记录-完成: eventNo={}, stocksId={}, strategy={}, decision={}",
                persisted.getEventNo(), persisted.getStocksId(), persisted.getStrategyType(),
                persisted.getPortfolioDecision());
        return persisted;
    }

    // ==================== 无限资金影子批次 ====================

    /**
     * 创建无限资金影子批次。
     * <p>
     * 为指定信号事件创建无限资金影子批次(ledgerType = UNLIMITED_SHADOW),
     * 批次状态初始为 ENTRY_PENDING,批次编号格式为 "S" + signalEventId。
     * 不分配正式槽位(slotId/slotNo 为 null),不受正式5槽限制。不发送即时群消息。
     *
     * @param event 关联的信号事件(须已保存,含主键ID)
     * @return 已保存的影子批次DO(含主键ID与批次编号)
     */
    public TornStockVirtualBatchDO createUnlimitedShadowBatch(TornStockSignalEventDO event) {
        Objects.requireNonNull(event, MSG_SIGNAL_EVENT_NULL);
        Objects.requireNonNull(event.getId(), MSG_SIGNAL_EVENT_ID_NULL);

        TornStockVirtualBatchDO existing = virtualBatchDao.selectBySignalEventIdAndLedgerTypeForUpdate(
                event.getId(), StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        if (existing != null) {
            return existing;
        }
        // 同股同策略同版本活跃无限资金影子批次全局唯一(uk_stock_virtual_batch_shadow_stock_strat_ver):
        // 积压/回放同一墙钟分钟处理多个历史round时, 同股同策略的第二个round必须复用已存在批次,
        // 否则触发唯一约束异常导致整轮回滚并进入FAILED_RETRYABLE。
        TornStockVirtualBatchDO existingByStock = virtualBatchDao
                .selectActiveUnlimitedShadowByStockStrategyForUpdate(
                        event.getStocksId(), event.getStrategyType(), event.getBuyRuleVersion());
        if (existingByStock != null) {
            log.info("同股同策略活跃无限资金影子批次已存在,复用: stocksId={}, strategy={}, batchNo={}, signalEventId={}",
                    event.getStocksId(), event.getStrategyType(), existingByStock.getBatchNo(), event.getId());
            return existingByStock;
        }

        TornStockVirtualBatchDO batch = buildBaseBatch(event);
        batch.setBatchNo(generateBatchNo(SHADOW_BATCH_NO_PREFIX, event.getId()));
        batch.setLedgerType(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());

        virtualBatchDao.insertIgnoreConflict(batch);
        TornStockVirtualBatchDO persisted = virtualBatchDao.selectBySignalEventIdAndLedgerTypeForUpdate(
                event.getId(), StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        if (persisted == null || persisted.getId() == null) {
            throw new IllegalStateException("无限资金影子批次插入后无法读取: signalEventId=" + event.getId());
        }
        log.info("无限资金影子批次创建-完成: batchNo={}, stocksId={}, signalEventId={}",
                persisted.getBatchNo(), persisted.getStocksId(), event.getId());
        return persisted;
    }

    // ==================== 拒绝观察批次 ====================

    /**
     * 创建拒绝观察批次。
     * <p>
     * 为被拒绝的信号事件创建拒绝观察批次(ledgerType = REJECTED_OBSERVATION),
     * 批次状态直接置为 CANCELLED(拒绝观察只跟踪理论路径,不进入买入流程),
     * 批次编号格式为 "R" + signalEventId。不占正式槽位、不发正式买入。
     *
     * @param event        关联的信号事件(须已保存,含主键ID)
     * @param rejectReason 拒绝原因编码
     */
    public void createRejectedObservationBatch(TornStockSignalEventDO event, String rejectReason) {
        Objects.requireNonNull(event, MSG_SIGNAL_EVENT_NULL);
        Objects.requireNonNull(event.getId(), MSG_SIGNAL_EVENT_ID_NULL);

        TornStockVirtualBatchDO existing = virtualBatchDao.selectBySignalEventIdAndLedgerTypeForUpdate(
                event.getId(), StockLedgerTypeEnum.REJECTED_OBSERVATION.getCode());
        if (existing != null) {
            return;
        }

        TornStockVirtualBatchDO batch = buildBaseBatch(event);
        batch.setBatchNo(generateBatchNo(REJECTED_BATCH_NO_PREFIX, event.getId()));
        batch.setLedgerType(StockLedgerTypeEnum.REJECTED_OBSERVATION.getCode());
        batch.setBatchStatus(StockBatchStatusEnum.CANCELLED.getCode());
        batch.setCancelReason(rejectReason);

        virtualBatchDao.insertIgnoreConflict(batch);
        TornStockVirtualBatchDO persisted = virtualBatchDao.selectBySignalEventIdAndLedgerTypeForUpdate(
                event.getId(), StockLedgerTypeEnum.REJECTED_OBSERVATION.getCode());
        if (persisted == null || persisted.getId() == null) {
            throw new IllegalStateException("拒绝观察批次插入后无法读取: signalEventId=" + event.getId());
        }
        log.info("拒绝观察批次创建-完成: batchNo={}, stocksId={}, signalEventId={}, rejectReason={}",
                persisted.getBatchNo(), persisted.getStocksId(), event.getId(), rejectReason);
    }

    /**
     * 回写拒绝观察的理论结果并标记事件已结算。
     *
     * @param event      已保存的拒绝观察事件
     * @param laterMfe   后续观察窗口最大有利偏移
     * @param laterMae   后续观察窗口最大不利偏移
     * @param resolvedAt 观察结果结算时间
     */
    public void resolveRejectedObservation(TornStockSignalEventDO event,
                                           BigDecimal laterMfe,
                                           BigDecimal laterMae,
                                           LocalDateTime resolvedAt) {
        Objects.requireNonNull(event, MSG_SIGNAL_EVENT_NULL);
        Objects.requireNonNull(event.getId(), MSG_SIGNAL_EVENT_ID_NULL);
        Objects.requireNonNull(resolvedAt, "观察结果结算时间不能为空");
        event.setLaterMfe(laterMfe);
        event.setLaterMae(laterMae);
        event.setResolvedAt(resolvedAt);
        signalEventDao.updateById(event);
        log.info("拒绝观察结果回写-完成: eventNo={}, resolvedAt={}", event.getEventNo(), resolvedAt);
    }

    /**
     * 更新信号事件的正式批次ID和影子批次ID。
     *
     * @param event 待更新的信号事件(须已保存,含主键ID与batchId字段)
     */
    public void updateEventBatchIds(TornStockSignalEventDO event) {
        Objects.requireNonNull(event, MSG_SIGNAL_EVENT_NULL);
        Objects.requireNonNull(event.getId(), MSG_SIGNAL_EVENT_ID_NULL);
        signalEventDao.updateById(event);
        log.info("信号事件批次ID回写: eventNo={}, formalBatchId={}, shadowBatchId={}",
                event.getEventNo(), event.getFormalBatchId(), event.getShadowBatchId());
    }

    // ==================== 轨道信号事件接入 ====================

    /**
     * 按目标轨道组合决策保存原始信号事件。
     * <p>
     * 正式候选(FORMAL)与候选影子候选(SHADOW)在候选接纳阶段提前保存事件,
     * 为对应批次提供非空signalEventId。
     *
     * @param evaluation    信号评估结果
     * @param candidateRank 候选排名
     * @param roundTime     轮次时间
     * @param decision      组合决策编码(FORMAL或SHADOW)
     * @return 已保存的信号事件
     */
    public TornStockSignalEventDO recordTrackSignalEvent(SignalEvaluationView evaluation,
                                                         Integer candidateRank,
                                                         LocalDateTime roundTime,
                                                         String decision) {
        return recordSignalEvent(buildSignalEventContext(
                evaluation, candidateRank, decision, null, roundTime));
    }

    /**
     * 回写正式批次ID到已保存的信号事件。
     *
     * @param event 已保存的信号事件
     */
    public void updateSignalEventBatchIds(TornStockSignalEventDO event) {
        updateEventBatchIds(event);
    }

    /**
     * 为候选影子批次链接其信号事件与无限资金影子孪生批次。
     * <p>
     * 候选影子接纳阶段已创建事件与候选影子批次,此处回填事件{@code shadowCandidateBatchId},
     * 并为同一信号建立无限资金影子孪生批次(保留所有可接纳信号的独立理论路径),
     * 最终一次性回写事件两个批次ID。
     *
     * @param event          已保存的信号事件
     * @param candidateBatch 候选影子批次(须已保存,含主键)
     */
    public void linkCandidateShadowEvent(TornStockSignalEventDO event,
                                         TornStockVirtualBatchDO candidateBatch) {
        Objects.requireNonNull(event, MSG_SIGNAL_EVENT_NULL);
        Objects.requireNonNull(candidateBatch, "候选影子批次不能为空");
        Objects.requireNonNull(candidateBatch.getId(), "候选影子批次主键不能为空");
        event.setShadowCandidateBatchId(candidateBatch.getId());
        TornStockVirtualBatchDO unlimitedShadow = createUnlimitedShadowBatch(event);
        event.setShadowBatchId(unlimitedShadow.getId());
        updateEventBatchIds(event);
        log.info("候选影子事件链接-完成: eventNo={}, shadowCandidateBatchId={}, shadowBatchId={}",
                event.getEventNo(), candidateBatch.getId(), unlimitedShadow.getId());
    }

    /**
     * 为全部信号评估结果写入原始信号事件、无限资金影子批次和拒绝观察批次,
     * 并回填已创建正式与候选影子批次的signalEventId。
     * <p>
     * 每个股票×策略×买入规则版本在同一轮只写入一次,避免重复评估结果造成重复事件和影子批次。
     * 对每个边沿触发的信号评估:
     * <ul>
     *   <li>记录原始信号事件(recordSignalEvent)</li>
     *   <li>ALLOWED且已入选正式 -> 回填对应正式批次的signalEventId</li>
     *   <li>ALLOWED且已入选候选影子 -> 回填对应候选影子批次的signalEventId,并创建无限资金影子批次</li>
     *   <li>ALLOWED且未入选任一槽位 -> 创建无限资金影子批次</li>
     *   <li>REJECTED/OBSERVED -> 创建拒绝观察批次</li>
     * </ul>
     *
     * @param allEvaluations            全部信号评估结果
     * @param newFormalBatches          本轮新建的正式批次列表(需回填signalEventId)
     * @param newCandidateShadowBatches 本轮新建的候选影子批次列表(需回填signalEventId)
     * @param candidateRankByStockId    候选排名映射(stocksId -> rank),供事件回写
     * @param allocationResultByStockId 候选实际接纳结果,供拒绝原因回写
     * @param roundTime                 本轮时间
     */
    public void writeShadowRecords(List<? extends SignalEvaluationView> allEvaluations,
                                   List<TornStockVirtualBatchDO> newFormalBatches,
                                   List<TornStockVirtualBatchDO> newCandidateShadowBatches,
                                   Map<Integer, Integer> candidateRankByStockId,
                                   Map<Integer, StockCandidateAllocationResultEnum> allocationResultByStockId,
                                   LocalDateTime roundTime) {
        if (allEvaluations == null || allEvaluations.isEmpty()) {
            return;
        }
        Map<Integer, TornStockVirtualBatchDO> formalBatchByStockId = indexBatchesByStockId(newFormalBatches);
        Map<Integer, TornStockVirtualBatchDO> candidateShadowBatchByStockId =
                indexBatchesByStockId(newCandidateShadowBatches);
        Set<String> writtenSignalKeys = new HashSet<>();
        for (SignalEvaluationView evaluation : allEvaluations) {
            if (isWritableSignalEvaluation(evaluation)) {
                String signalKey = buildSignalKey(evaluation);
                if (writtenSignalKeys.add(signalKey)) {
                    Integer rank = candidateRankByStockId != null
                            ? candidateRankByStockId.get(evaluation.stocksId()) : null;
                    writeSingleShadowRecord(evaluation, formalBatchByStockId.get(evaluation.stocksId()),
                            candidateShadowBatchByStockId.get(evaluation.stocksId()), rank,
                            allocationResultByStockId, roundTime);
                } else {
                    log.debug("同轮重复信号评估已跳过: key={}", signalKey);
                }
            }
        }
    }

    /**
     * 判断评估结果是否允许进入影子记录写入流程。
     *
     * @param evaluation 信号评估结果
     * @return 非空、触发边沿且存在主策略时返回true
     */
    private boolean isWritableSignalEvaluation(SignalEvaluationView evaluation) {
        return evaluation != null && evaluation.edgeTriggered() && evaluation.primaryStrategy() != null;
    }

    /**
     * 写入单个边沿触发信号的影子记录。
     * <p>
     * 组装信号事件上下文(含月度风格字段与信号参考价)并记录事件,然后根据组合决策:
     * 回填正式/候选影子批次ID、创建无限资金影子批次,或创建拒绝观察批次。
     * 候选影子批次已由候选接纳阶段创建,此处仅回填其signalEventId并继续建立无限资金影子路径。
     *
     * @param evaluation                信号评估结果
     * @param formalBatch               对应股票的正式批次;FORMAL决策时回填其signalEventId,可为null
     * @param candidateShadowBatch      对应股票的候选影子批次;SHADOW决策时回填其signalEventId,可为null
     * @param candidateRank             候选排名;未入选正式时为null
     * @param allocationResultByStockId 候选实际接纳结果
     * @param roundTime                 本轮时间
     */
    private void writeSingleShadowRecord(SignalEvaluationView evaluation,
                                         TornStockVirtualBatchDO formalBatch,
                                         TornStockVirtualBatchDO candidateShadowBatch,
                                         Integer candidateRank,
                                         Map<Integer, StockCandidateAllocationResultEnum> allocationResultByStockId,
                                         LocalDateTime roundTime) {
        EligibilityResult eligibility = evaluation.eligibilityResult();
        String portfolioDecision = determinePortfolioDecision(
                evaluation, eligibility, formalBatch);
        String rejectReason = determineRejectReason(
                eligibility, allocationResultByStockId == null ? null : allocationResultByStockId.get(evaluation.stocksId()));

        if (DECISION_FORMAL.equals(portfolioDecision)
                && formalBatch != null && formalBatch.getSignalEventId() != null) {
            return;
        }
        // 候选影子批次在接纳阶段已创建并回填signalEventId与无限资金孪生批次,
        // 事件已完整链接,无需在此重复创建。
        if (DECISION_SHADOW.equals(portfolioDecision)
                && candidateShadowBatch != null && candidateShadowBatch.getSignalEventId() != null) {
            return;
        }

        StockSignalEventContext eventContext = buildSignalEventContext(
                evaluation, candidateRank, portfolioDecision, rejectReason, roundTime);
        TornStockSignalEventDO event = recordSignalEvent(eventContext);

        if (DECISION_FORMAL.equals(portfolioDecision) && formalBatch != null) {
            formalBatch.setSignalEventId(event.getId());
            event.setFormalBatchId(formalBatch.getId());
            updateEventBatchIds(event);
        } else if (DECISION_SHADOW.equals(portfolioDecision)) {
            if (candidateShadowBatch != null) {
                candidateShadowBatch.setSignalEventId(event.getId());
                event.setShadowCandidateBatchId(candidateShadowBatch.getId());
            }
            TornStockVirtualBatchDO shadowBatch = createUnlimitedShadowBatch(event);
            event.setShadowBatchId(shadowBatch.getId());
            updateEventBatchIds(event);
        } else if (DECISION_REJECTED.equals(portfolioDecision)) {
            createRejectedObservationBatch(event, rejectReason);
        }
    }

    /**
     * 构建信号事件上下文。
     *
     * @param evaluation        信号评估结果
     * @param candidateRank     候选排名
     * @param portfolioDecision 组合决策
     * @param rejectReason      拒绝原因
     * @param roundTime         轮次时间
     * @return 信号事件上下文
     */
    private StockSignalEventContext buildSignalEventContext(SignalEvaluationView evaluation,
                                                            Integer candidateRank,
                                                            String portfolioDecision,
                                                            String rejectReason,
                                                            LocalDateTime roundTime) {
        TornStockMonthlyStateDO monthlyState = evaluation.monthlyState();
        BuyContext context = evaluation.context();
        EligibilityResult eligibility = evaluation.eligibilityResult();
        return new StockSignalEventContext(
                evaluation.stocksId(),
                evaluation.stocksShortname(),
                evaluation.primaryStrategy().getStrategyType().getCode(),
                context != null ? context.referencePrice() : null,
                monthlyState != null ? monthlyState.getStrategyFitPrior() : null,
                monthlyState != null ? monthlyState.getMaturity() : null,
                monthlyState != null ? monthlyState.getRiskLevel() : null,
                monthlyState != null ? monthlyState.getEffectiveMonth() : null,
                StockRuleVersion.BUY,
                evaluation.qualityScore(),
                buildFeatureSnapshot(context),
                buildStyleSnapshot(monthlyState),
                eligibility != null ? eligibility.result().getCode() : null,
                eligibility != null ? eligibility.reasons() : List.of(),
                candidateRank,
                portfolioDecision,
                rejectReason,
                roundTime
        );
    }

    /**
     * 判定组合决策编码。
     * <p>
     * ALLOWED且已入选正式 -> FORMAL; ALLOWED但未入选(无槽位/资金不足) -> SHADOW;
     * REJECTED/OBSERVED -> REJECTED。
     *
     * @param evaluation  信号评估
     * @param eligibility 资格结果
     * @param formalBatch 本轮实际创建的正式批次,为空表示未实际接纳
     * @return 组合决策编码
     */
    private String determinePortfolioDecision(SignalEvaluationView evaluation,
                                              EligibilityResult eligibility,
                                              TornStockVirtualBatchDO formalBatch) {
        if (eligibility == null || StockEligibilityResultEnum.ALLOWED != eligibility.result()) {
            return DECISION_REJECTED;
        }
        if (!evaluation.acceptedFormal() || formalBatch == null || formalBatch.getId() == null) {
            return DECISION_SHADOW;
        }
        return DECISION_FORMAL;
    }

    /**
     * 判定拒绝原因编码。
     * <p>
     * 非拒绝时返回null;拒绝但无原因时返回{@value #UNKNOWN_REJECT_REASON};
     * 否则返回原因列表的首个编码。正式分配与候选影子分配都不视为拒绝。
     *
     * @param eligibility      资格结果
     * @param allocationResult 候选实际接纳结果
     * @return 拒绝原因编码;非拒绝时返回null
     */
    private String determineRejectReason(EligibilityResult eligibility,
                                         StockCandidateAllocationResultEnum allocationResult) {
        if (allocationResult != null
                && allocationResult != StockCandidateAllocationResultEnum.FORMAL_ALLOCATED
                && allocationResult != StockCandidateAllocationResultEnum.SHADOW_CANDIDATE_ALLOCATED) {
            return allocationResult.getCode();
        }
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
     * 构建同轮信号幂等键。
     *
     * @param evaluation 信号评估结果
     * @return 股票、策略和买入规则版本组成的键
     */
    private String buildSignalKey(SignalEvaluationView evaluation) {
        return evaluation.stocksId() + "|"
                + evaluation.primaryStrategy().getStrategyType().getCode() + "|"
                + StockRuleVersion.BUY;
    }

    /**
     * 按股票ID索引本轮新建批次(正式或候选影子共用)。
     *
     * @param newBatches 本轮新建批次列表
     * @return 按股票ID索引的批次
     */
    private Map<Integer, TornStockVirtualBatchDO> indexBatchesByStockId(
            List<TornStockVirtualBatchDO> newBatches) {
        Map<Integer, TornStockVirtualBatchDO> map = new HashMap<>();
        if (newBatches == null) {
            return map;
        }
        for (TornStockVirtualBatchDO batch : newBatches) {
            if (batch != null && batch.getStocksId() != null) {
                map.put(batch.getStocksId(), batch);
            }
        }
        return map;
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

    // ==================== 辅助方法 ====================

    /**
     * 生成事件编号。
     * <p>
     * 格式: "E" + 业务轮次yyyyMMddHHmm + stocksId + strategyType前3字符。
     * 事件编号由稳定业务轮次生成,重试不会因当前墙钟分钟变化而产生不同编号。
     *
     * @param roundTime    业务轮次时间
     * @param stocksId     股票ID
     * @param strategyType 策略类型编码
     * @return 事件编号
     */
    private String generateEventNo(LocalDateTime roundTime, Integer stocksId, String strategyType) {
        String timestamp = roundTime.format(NO_TIMESTAMP_FORMATTER);
        String strategySuffix = truncateStrategyType(strategyType);
        return EVENT_NO_PREFIX + timestamp + stocksId + strategySuffix;
    }

    /**
     * 生成批次编号。
     * <p>
     * 格式: prefix + signalEventId,保证同一事件不同账本编号稳定且可重试复用。
     *
     * @param prefix  批次编号前缀("S"或"R")
     * @param eventId 来源信号事件ID
     * @return 批次编号
     */
    private String generateBatchNo(String prefix, Long eventId) {
        return prefix + eventId;
    }

    /**
     * 截取策略类型编码前3字符用于事件编号后缀。
     * <p>
     * 策略类型不足3字符时取全部字符。
     *
     * @param strategyType 策略类型编码
     * @return 截取后的策略后缀
     */
    private String truncateStrategyType(String strategyType) {
        if (strategyType.length() <= STRATEGY_TYPE_TRUNCATE_LENGTH) {
            return strategyType;
        }
        return strategyType.substring(0, STRATEGY_TYPE_TRUNCATE_LENGTH);
    }

    /**
     * 将资格审查原因列表转换为JSON文本。
     * <p>
     * 原因列表为空或null时返回空数组JSON "[]"。使用项目统一的
     * {@link JsonUtils} 保证与项目其他JSON序列化口径一致。
     *
     * @param reasons 原因编码列表
     * @return JSON文本
     */
    private String convertReasonsToJson(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "[]";
        }
        return JsonUtils.objToJson(reasons);
    }

    /**
     * 基于信号事件构建批次基础字段(不含批次编号、账本类型、批次状态)。
     * <p>
     * 复用股票ID、简称、主策略、质量评分、信号事件ID、信号时间等公共字段填充逻辑,
     * 影子批次与拒绝观察批次共用此基础构建。slotId/slotNo 保持为 null(不占正式槽位)。
     * 月度风格字段(stylePrior/styleMaturity/riskLevel/styleEffectiveMonth/styleRuleVersion/
     * riskRuleVersion)与信号参考价、预期入场bar时间、入场超时时间、卖出/分配/消息规则版本
     * 一并填充,保证影子与拒绝观察批次与正式批次的Schema NOT NULL字段口径一致。
     *
     * @param event 关联的信号事件
     * @return 已填充基础字段的批次DO
     */
    private TornStockVirtualBatchDO buildBaseBatch(TornStockSignalEventDO event) {
        validatePersistedEventFields(event);
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setStocksId(event.getStocksId());
        batch.setStocksShortname(event.getStocksShortname());
        batch.setPrimaryStrategy(event.getStrategyType());
        batch.setMatchedStrategies(JsonUtils.objToJson(List.of(event.getStrategyType())));
        batch.setQualityScore(event.getQualityScore());
        batch.setSignalEventId(event.getId());
        batch.setSignalTime(event.getRoundTime());
        TornStockVirtualBatchSignalFields fields = StockVirtualBatchAssembler.buildSignalFields(event);
        StockVirtualBatchAssembler.applySignalFields(batch, fields);
        // slotId/slotNo 保持 null: 影子与拒绝观察批次不占正式槽位
        return batch;
    }

    /**
     * 校验已持久化信号事件的关键字段是否满足批次构造前置条件。
     * <p>
     * {@code buildBaseBatch} 依赖事件的主要字段构造影子/拒绝观察批次,而这些字段在
     * {@code torn_stock_signal_event} 中均为 NOT NULL。当冲突插入后读回的对象缺失字段时,
     * 必须在构造批次前抛出包含 eventId 与字段名的持久化契约异常,而不是在
     * {@code List.of(event.getStrategyType())} 处产生无上下文的 NullPointerException。
     *
     * @param event 已持久化的信号事件
     * @throws IllegalStateException 任一关键字段缺失时抛出
     */
    private void validatePersistedEventFields(TornStockSignalEventDO event) {
        Objects.requireNonNull(event, MSG_SIGNAL_EVENT_NULL);
        List<String> missing = new ArrayList<>();
        if (event.getId() == null) {
            missing.add("id");
        }
        if (event.getStocksId() == null) {
            missing.add("stocksId");
        }
        if (event.getStocksShortname() == null) {
            missing.add("stocksShortname");
        }
        if (event.getStrategyType() == null) {
            missing.add("strategyType");
        }
        if (event.getQualityScore() == null) {
            missing.add("qualityScore");
        }
        if (event.getRoundTime() == null) {
            missing.add("roundTime");
        }
        if (event.getSignalReferencePrice() == null) {
            missing.add("signalReferencePrice");
        }
        if (event.getBuyRuleVersion() == null) {
            missing.add("buyRuleVersion");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("信号事件持久化字段缺失,无法构造影子/拒绝观察批次: eventId="
                    + event.getId() + ", missingFields=" + missing);
        }
    }

    /**
     * 信号评估结果接口 - 供影子轨道记录器消费。
     * <p>
     * 从评估器的内部SignalEvaluation抽象出的最小只读视图,
     * 使记录器不直接依赖评估器内部实现,降低耦合。
     *
     * @author Bai
     * @version 1.2.14
     * @since 2026.08.09
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
         * 是否为false->true边沿触发。
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
         * 是否已被正式组合接纳。
         *
         * @return 已接纳时返回true
         */
        boolean acceptedFormal();
    }
}
