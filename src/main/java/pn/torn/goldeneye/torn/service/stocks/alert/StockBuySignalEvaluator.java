package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService.EligibilityResult;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.StockCandidateRankingPolicy;
import pn.torn.goldeneye.utils.JsonUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 股票买入信号评估器 - 执行轮次事务步骤6-7,评估买入信号(false-&gt;true边沿)与资格,
 * 排序候选并接纳正式候选。
 * <p>
 * 从 {@link StockRoundTransactionService} 拆分而来,消除原 732 行 Brain Method。
 * 职责仅包含:
 * <ol>
 *   <li>步骤6: 对每支有特征数据的股票评估买入信号,收集通过资格的正式候选</li>
 *   <li>步骤7: 按排序结果接纳正式候选,查找可用槽位并预留</li>
 * </ol>
 * 信号事件写入、影子批次与拒绝观察批次由 {@link StockShadowService} 消费
 * {@link BuySignalResult#allEvaluations()} 完成,不在本类职责范围内。
 *
 * <h3>Sonar修复要点</h3>
 * <ul>
 *   <li>原 Brain Method 拆分为 {@link #evaluateSignals} + {@link #evaluateSingleStock}
 *       + {@link #matchStrategies},降低认知复杂度</li>
 *   <li>{@link SignalEvaluation} 用 record + Builder 模式消除 10 参数构造器问题</li>
 *   <li>{@link #createFormalBatch} 7 参数用 {@link FormalBatchContext} record 封装</li>
 *   <li>循环内多个 continue 提取为 {@link #evaluateSingleStock} 返回 null</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockBuySignalEvaluator {

    /**
     * 正式批次编号前缀
     */
    private static final String FORMAL_BATCH_NO_PREFIX = "F";
    /**
     * 正式批次编号时间戳格式
     */
    private static final String FORMAL_BATCH_NO_TIMESTAMP_PATTERN = "yyyyMMddHHmm";
    /**
     * 正式批次编号格式化器
     */
    private static final DateTimeFormatter FORMAL_BATCH_NO_FORMATTER =
            DateTimeFormatter.ofPattern(FORMAL_BATCH_NO_TIMESTAMP_PATTERN);

    private final List<StockBuyStrategy> buyStrategies;
    private final StockEligibilityService eligibilityService;
    private final StockPortfolioService portfolioService;
    private final StockCandidateRankingPolicy candidateRankingPolicy;
    private final TornStockVirtualBatchDAO virtualBatchDao;

    // ==================== 步骤6: 评估买入信号 ====================

    /**
     * 评估本轮买入信号(false-&gt;true边沿)与资格,收集正式候选与全部评估结果。
     * <p>
     * 对每支 strategyReady 的股票:
     * <ol>
     *   <li>组装 {@link BuyContext}</li>
     *   <li>调用 {@link #matchStrategies} 遍历买入策略,选取主策略(质量分最高)</li>
     *   <li>比较 signalState.conditionActive 与本轮 matches 结果,判断 false-&gt;true 边沿</li>
     *   <li>边沿触发时调用 {@link StockEligibilityService#checkEligibility}</li>
     *   <li>ALLOWED 的候选加入正式候选列表</li>
     * </ol>
     * 单支股票的评估逻辑提取为 {@link #evaluateSingleStock},返回 null 表示该股票被跳过。
     *
     * @param snapshot            轮次快照
     * @param barByStock          按股票ID索引的bar映射
     * @param monthlyStateByStock 按股票ID索引的月度状态映射
     * @param signalStateByStock  按股票ID索引的信号状态映射
     * @param roundTime           本轮bar开始时间(决策锚点)
     * @return 买入信号评估结果,包含正式候选列表与全部评估结果
     */
    public BuySignalResult evaluateSignals(
            RoundSnapshot snapshot,
            Map<Integer, TornStockMarketBar15mDO> barByStock,
            Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock,
            Map<Integer, TornStockSignalStateDO> signalStateByStock,
            LocalDateTime roundTime) {
        Objects.requireNonNull(snapshot, "轮次快照不能为空");
        Objects.requireNonNull(roundTime, "轮次时间不能为空");

        Set<Integer> activeFormalStockIds = collectActiveFormalStockIds(snapshot.activeBatches());
        List<CandidateInfo> formalCandidates = new ArrayList<>();
        List<SignalEvaluation> allEvaluations = new ArrayList<>();

        for (TornStockStrategyFeature15mDO feature : snapshot.features()) {
            SignalEvaluation evaluation = evaluateSingleStock(
                    feature, barByStock, monthlyStateByStock, signalStateByStock,
                    activeFormalStockIds);
            if (evaluation == null) {
                continue;
            }
            allEvaluations.add(evaluation);
            if (evaluation.acceptedFormal()) {
                formalCandidates.add(buildCandidateInfo(evaluation));
            }
        }
        return new BuySignalResult(formalCandidates, allEvaluations);
    }

    /**
     * 评估单支股票的买入信号,返回包含全部中间结果的 {@link SignalEvaluation}。
     * <p>
     * 以下情况返回 null(原 Brain Method 中的多个 continue 分支):
     * <ul>
     *   <li>strategyReady 不为 true</li>
     *   <li>本轮 bar 缺失或不可用</li>
     *   <li>风格缺失导致 {@link BuyContext} 构建失败</li>
     * </ul>
     * 边沿触发且资格 ALLOWED 时,将 {@link SignalEvaluation.Builder#acceptedFormal(boolean)}
     * 置为 true 并填充 {@link SignalEvaluation.Builder#eligibilityResult(EligibilityResult)}。
     *
     * @param feature              该股票的策略特征
     * @param barByStock           按股票ID索引的bar映射
     * @param monthlyStateByStock  按股票ID索引的月度状态映射
     * @param signalStateByStock   按股票ID索引的信号状态映射
     * @param activeFormalStockIds 已有正式活跃批次的股票ID集合
     * @return 信号评估结果;被跳过时返回 null
     */
    private SignalEvaluation evaluateSingleStock(
            TornStockStrategyFeature15mDO feature,
            Map<Integer, TornStockMarketBar15mDO> barByStock,
            Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock,
            Map<Integer, TornStockSignalStateDO> signalStateByStock,
            Set<Integer> activeFormalStockIds) {
        if (!Boolean.TRUE.equals(feature.getStrategyReady())) {
            return null;
        }

        Integer stocksId = feature.getStocksId();
        TornStockMarketBar15mDO bar = barByStock.get(stocksId);
        if (bar == null || !Stock15mBarBuildService.isUsable(bar)) {
            return null;
        }

        TornStockMonthlyStateDO monthlyState = monthlyStateByStock.get(stocksId);
        BuyContext context = buildBuyContext(feature, monthlyState);
        if (context == null) {
            return null;
        }

        StrategyMatchResult matchResult = matchStrategies(context);
        boolean currentMatches = !matchResult.matchedStrategies().isEmpty();
        TornStockSignalStateDO signalState = signalStateByStock.get(stocksId);
        boolean edgeTriggered = checkEdgeTriggered(currentMatches, signalState);

        SignalEvaluation.Builder builder = SignalEvaluation.builder(stocksId, context.stocksShortname())
                .primaryStrategy(matchResult.primaryStrategy())
                .matchedStrategies(matchResult.matchedStrategies())
                .qualityScore(matchResult.bestScore())
                .currentMatches(currentMatches)
                .edgeTriggered(edgeTriggered)
                .context(context)
                .signalState(signalState)
                .monthlyState(monthlyState);

        if (!edgeTriggered) {
            return builder.build();
        }

        boolean hasActiveFormalBatch = activeFormalStockIds.contains(stocksId);
        EligibilityResult eligibility = eligibilityService.checkEligibility(
                context, signalState, monthlyState, hasActiveFormalBatch);
        builder.eligibilityResult(eligibility);

        boolean accepted = StockEligibilityResultEnum.ALLOWED == eligibility.result()
                && matchResult.primaryStrategy() != null;
        builder.acceptedFormal(accepted);

        if (accepted) {
            log.info("买入信号通过资格: stocksId={}, strategy={}, score={}",
                    stocksId, matchResult.primaryStrategy().getStrategyType(), matchResult.bestScore());
        } else {
            log.info("买入信号未通过资格: stocksId={}, result={}, reasons={}",
                    stocksId, eligibility.result(), eligibility.reasons());
        }
        return builder.build();
    }

    /**
     * 遍历全部买入策略,返回命中的策略列表、主策略(质量分最高)与最优质量分。
     * <p>
     * 对每个策略先调用 {@link StockBuyStrategy#isApplicableStyle} 校验风格适配,
     * 再调用 {@link StockBuyStrategy#matches} 判断是否命中。命中的策略计入
     * {@code matchedStrategies} 并计算质量分,质量分最高的策略作为主策略。
     *
     * @param context 买入上下文
     * @return 策略匹配结果,无命中时 primaryStrategy 为 null、bestScore 为 null
     */
    private StrategyMatchResult matchStrategies(BuyContext context) {
        List<StockBuyStrategy> matchedStrategies = new ArrayList<>();
        StockBuyStrategy primaryStrategy = null;
        BigDecimal bestScore = null;

        for (StockBuyStrategy strategy : buyStrategies) {
            if (!isStrategyMatched(strategy, context)) {
                continue;
            }
            matchedStrategies.add(strategy);
            BigDecimal score = strategy.calculateQualityScore(context);
            if (primaryStrategy == null || (bestScore != null && score.compareTo(bestScore) > 0)) {
                primaryStrategy = strategy;
                bestScore = score;
            }
        }
        return new StrategyMatchResult(primaryStrategy, matchedStrategies, bestScore);
    }

    /**
     * 判断策略是否适配风格且命中买入条件。
     *
     * @param strategy 买入策略
     * @param context  买入上下文
     * @return true表示风格适配且命中
     */
    private boolean isStrategyMatched(StockBuyStrategy strategy, BuyContext context) {
        return strategy.isApplicableStyle(context.stylePrior()) && strategy.matches(context);
    }

    /**
     * 判断本轮是否为 false-&gt;true 边沿触发。
     * <p>
     * 当本轮命中任一策略(currentMatches)且上轮 conditionActive 不为 true 时为边沿触发。
     *
     * @param currentMatches 本轮是否命中任何策略
     * @param signalState    信号状态记录,可为 null
     * @return true 表示本轮为 false-&gt;true 边沿触发
     */
    private boolean checkEdgeTriggered(boolean currentMatches, TornStockSignalStateDO signalState) {
        boolean previousActive = signalState != null && Boolean.TRUE.equals(signalState.getConditionActive());
        return currentMatches && !previousActive;
    }

    /**
     * 从特征组装 {@link BuyContext}。
     *
     * @param feature      策略特征
     * @param monthlyState 月度状态
     * @return BuyContext;风格缺失时返回 null
     */
    private BuyContext buildBuyContext(TornStockStrategyFeature15mDO feature,
                                       TornStockMonthlyStateDO monthlyState) {
        StockStrategyFitEnumWrapper styleWrapper = parseStyle(monthlyState);
        if (styleWrapper == null) {
            return null;
        }
        return new BuyContext(
                feature.getStocksId(),
                feature.getStocksShortname(),
                feature.getReferencePrice(),
                feature.getMa1d(),
                feature.getMa7d(),
                feature.getMa30d(),
                feature.getZscore1d(),
                feature.getZscore7d(),
                feature.getZscore30d(),
                feature.getReturn6h(),
                feature.getReturn1d(),
                feature.getReturn7d(),
                feature.getReturn14d(),
                feature.getLow30d(),
                feature.getHigh30d(),
                feature.getWidth30d(),
                feature.getPosition30(),
                feature.getPctAbove30dLow(),
                feature.getPctBelow30dHigh(),
                feature.getStrategyReady(),
                styleWrapper.style(),
                styleWrapper.maturity(),
                styleWrapper.riskLevel()
        );
    }

    /**
     * 从月度状态解析风格、成熟度、风险等级。
     *
     * @param monthlyState 月度状态
     * @return 风格包装对象;风格为空或解析失败时返回 null
     */
    private StockStrategyFitEnumWrapper parseStyle(TornStockMonthlyStateDO monthlyState) {
        if (monthlyState == null) {
            return null;
        }
        if (monthlyState.getStrategyFitPrior() == null || monthlyState.getStrategyFitPrior().isBlank()) {
            return null;
        }
        try {
            StockStrategyFitEnum style = StockStrategyFitEnum.fromCode(monthlyState.getStrategyFitPrior());
            StockMaturityEnum maturity = monthlyState.getMaturity() != null
                    ? StockMaturityEnum.fromCode(monthlyState.getMaturity()) : null;
            StockRiskLevelEnum riskLevel = monthlyState.getRiskLevel() != null
                    ? StockRiskLevelEnum.fromCode(monthlyState.getRiskLevel()) : null;
            return new StockStrategyFitEnumWrapper(style, maturity, riskLevel);
        } catch (IllegalArgumentException e) {
            log.warn("月度状态风格解析失败: strategyFitPrior={}, error={}",
                    monthlyState.getStrategyFitPrior(), e.getMessage());
            return null;
        }
    }

    /**
     * 从信号评估结果构建候选信息。
     *
     * @param evaluation 已通过资格的信号评估
     * @return 候选信息
     */
    private CandidateInfo buildCandidateInfo(SignalEvaluation evaluation) {
        List<String> matchedStrategyCodes = evaluation.matchedStrategies().stream()
                .map(s -> s.getStrategyType().getCode())
                .toList();
        return new CandidateInfo(
                evaluation.stocksId(),
                evaluation.stocksShortname(),
                evaluation.primaryStrategy().getStrategyType(),
                matchedStrategyCodes,
                evaluation.qualityScore()
        );
    }

    /**
     * 收集所有有正式活跃批次的股票ID集合。
     *
     * @param activeBatches 活跃批次列表
     * @return 有正式活跃批次的股票ID集合
     */
    private Set<Integer> collectActiveFormalStockIds(List<TornStockVirtualBatchDO> activeBatches) {
        Set<Integer> stockIds = new HashSet<>();
        if (activeBatches == null) {
            return stockIds;
        }
        for (TornStockVirtualBatchDO batch : activeBatches) {
            if (StockLedgerTypeEnum.FORMAL.getCode().equals(batch.getLedgerType())
                    && batch.getStocksId() != null) {
                StockBatchStatusEnum status = StockBatchStatusEnum.fromCode(batch.getBatchStatus());
                if (status.isActive()) {
                    stockIds.add(batch.getStocksId());
                }
            }
        }
        return stockIds;
    }

    // ==================== 步骤7: 接纳正式候选 ====================

    /**
     * 按排序结果接纳正式候选,检查可用槽位并预留,返回新建的正式批次列表。
     * <p>
     * 遍历排序后的候选列表,对每个候选:
     * <ol>
     *   <li>查找可用槽位(findAvailableSlot);无槽位时停止接纳</li>
     *   <li>计算股数(calculateQuantity);股数不足时跳过该候选</li>
     *   <li>创建正式批次(ENTRY_PENDING),预留槽位</li>
     * </ol>
     * 单个候选的接纳逻辑提取为 {@link #acceptSingleCandidate},返回 null 表示该候选被跳过。
     *
     * @param rankedCandidates 排序后的候选列表
     * @param snapshot         轮次快照
     * @param barByStock       按股票ID索引的bar映射
     * @param roundTime        本轮时间
     * @return 新建的正式批次列表
     */
    public List<TornStockVirtualBatchDO> acceptCandidates(
            List<CandidateInfo> rankedCandidates,
            RoundSnapshot snapshot,
            Map<Integer, TornStockMarketBar15mDO> barByStock,
            LocalDateTime roundTime) {
        Objects.requireNonNull(roundTime, "轮次时间不能为空");
        List<TornStockVirtualBatchDO> newFormalBatches = new ArrayList<>();
        if (rankedCandidates == null || rankedCandidates.isEmpty()) {
            return newFormalBatches;
        }

        int candidateRank = 0;
        for (CandidateInfo candidate : rankedCandidates) {
            candidateRank++;
            Optional<TornStockPortfolioSlotDO> slotOpt = findFirstAvailableFromSnapshot(snapshot);
            if (slotOpt.isEmpty()) {
                log.info("无可用槽位,停止接纳候选: stocksId={}, rank={}", candidate.stocksId(), candidateRank);
                break;
            }

            TornStockVirtualBatchDO batch = acceptSingleCandidate(
                    candidate, slotOpt.get(), barByStock.get(candidate.stocksId()), candidateRank, roundTime);
            if (batch != null) {
                newFormalBatches.add(batch);
            }
        }
        return newFormalBatches;
    }

    /**
     * 从内存快照中查找首个可用槽位,避免数据库查询
     *
     * @param snapshot 轮次快照
     * @return 首个AVAILABLE槽位;无则返回empty
     */
    private Optional<TornStockPortfolioSlotDO> findFirstAvailableFromSnapshot(RoundSnapshot snapshot) {
        return snapshot.slots().stream()
                .filter(slot -> StockSlotStatusEnum.AVAILABLE.getCode().equals(slot.getSlotStatus()))
                .findFirst();
    }

    /**
     * 接纳单个候选:校验 bar 与股数,创建正式批次并预留槽位。
     * <p>
     * 以下情况返回 null(原循环中的 continue 分支):
     * <ul>
     *   <li>bar 缺失或价格无效</li>
     *   <li>可用资金不足买入1股</li>
     * </ul>
     *
     * @param candidate 候选信息
     * @param slot      已分配的可用槽位
     * @param bar       该候选股票本轮bar
     * @param rank      候选排名(1起始)
     * @param roundTime 本轮时间
     * @return 新建的正式批次;被跳过时返回 null
     */
    private TornStockVirtualBatchDO acceptSingleCandidate(
            CandidateInfo candidate,
            TornStockPortfolioSlotDO slot,
            TornStockMarketBar15mDO bar,
            int rank,
            LocalDateTime roundTime) {
        if (bar == null || bar.getLastPrice() == null || bar.getLastPrice().signum() <= 0) {
            log.warn("候选[{}]本轮bar无效,跳过", candidate.stocksId());
            return null;
        }

        BigDecimal signalReferencePrice = bar.getLastPrice();
        BigDecimal reservedAmount = slot.getAvailableCash();
        Long quantity = StockPortfolioService.calculateQuantity(reservedAmount, signalReferencePrice);
        if (quantity <= 0) {
            log.info("候选[{}]可用资金不足买入1股,跳过: availableCash={}, price={}",
                    candidate.stocksId(), reservedAmount, signalReferencePrice);
            return null;
        }

        FormalBatchContext ctx = new FormalBatchContext(
                candidate, slot, bar, signalReferencePrice, quantity, roundTime, rank);
        TornStockVirtualBatchDO batch = createFormalBatch(ctx);

        virtualBatchDao.save(batch);

        portfolioService.reserveSlot(slot, reservedAmount, batch.getId());

        log.info("正式候选接纳: stocksId={}, rank={}, slotNo={}, signalPrice={}, quantity={}, reserved={}, batchId={}",
                candidate.stocksId(), rank, slot.getSlotNo(),
                signalReferencePrice, quantity, reservedAmount, batch.getId());
        return batch;
    }

    /**
     * 创建正式批次DO(ENTRY_PENDING状态)。
     * <p>
     * 使用 {@link FormalBatchContext} 封装参数,避免超过 Sonar 方法参数上限(7)。
     *
     * @param ctx 正式批次构建上下文
     * @return 未保存的正式批次DO
     */
    private TornStockVirtualBatchDO createFormalBatch(FormalBatchContext ctx) {
        CandidateInfo candidate = ctx.candidate();
        TornStockPortfolioSlotDO slot = ctx.slot();
        LocalDateTime roundTime = ctx.roundTime();

        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setBatchNo(FORMAL_BATCH_NO_PREFIX
                + roundTime.format(FORMAL_BATCH_NO_FORMATTER) + candidate.stocksId());
        batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        batch.setStocksId(candidate.stocksId());
        batch.setStocksShortname(candidate.stocksShortname());
        batch.setPrimaryStrategy(candidate.primaryStrategy().getCode());
        batch.setMatchedStrategies(JsonUtils.objToJson(candidate.matchedStrategies()));
        batch.setQualityScore(candidate.qualityScore());
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        batch.setSlotId(slot.getId());
        batch.setSlotNo(slot.getSlotNo());
        batch.setSignalTime(roundTime);
        batch.setSignalReferencePrice(ctx.signalReferencePrice());
        batch.setExpectedEntryBarTime(roundTime.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES));
        batch.setEntryStaleAt(StockPortfolioService.calculateEntryStaleAt(roundTime));
        batch.setQuantity(ctx.quantity());
        batch.setBuyRuleVersion(StockRoundTransactionService.BUY_RULE_VERSION);
        batch.setSellRuleVersion(StockRoundTransactionService.SELL_RULE_VERSION);
        batch.setAllocationRuleVersion(StockRoundTransactionService.ALLOCATION_RULE_VERSION);
        batch.setMessageRuleVersion(StockRoundTransactionService.MESSAGE_RULE_VERSION);
        batch.setResetObserved(false);
        return batch;
    }

    // ==================== 内部值对象 ====================

    /**
     * 买入信号评估结果。
     *
     * @param formalCandidates 通过资格的正式候选列表
     * @param allEvaluations   全部信号评估结果(含拒绝/观察)
     */
    public record BuySignalResult(
            List<CandidateInfo> formalCandidates,
            List<SignalEvaluation> allEvaluations
    ) {
    }

    /**
     * 信号评估结果 - 封装单支股票本轮买入信号评估的全部中间结果。
     * <p>
     * 使用 record + Builder 模式消除原 10 参数构造器的 Sonar 问题。
     * 由 {@link #evaluateSingleStock} 生成,供后续影子记录、通知审计与信号状态更新消费。
     *
     * @param stocksId          股票ID
     * @param stocksShortname   股票简称
     * @param primaryStrategy   主策略(质量分最高的命中策略)
     * @param matchedStrategies 全部命中策略列表
     * @param qualityScore      主策略质量分
     * @param currentMatches    本轮是否命中任何策略
     * @param edgeTriggered     是否为 false-&gt;true 边沿触发
     * @param context           买入上下文
     * @param signalState       信号状态记录
     * @param monthlyState      月度状态记录
     * @param eligibilityResult 资格判定结果;非边沿触发时为 null
     * @param candidateRank     候选排名;未进入候选时为 null
     * @param acceptedFormal    是否被正式接纳
     */
    public record SignalEvaluation(
            Integer stocksId,
            String stocksShortname,
            StockBuyStrategy primaryStrategy,
            List<StockBuyStrategy> matchedStrategies,
            BigDecimal qualityScore,
            boolean currentMatches,
            boolean edgeTriggered,
            BuyContext context,
            TornStockSignalStateDO signalState,
            TornStockMonthlyStateDO monthlyState,
            EligibilityResult eligibilityResult,
            Integer candidateRank,
            boolean acceptedFormal
    ) implements StockShadowRecordWriter.SignalEvaluationView {
        /**
         * 创建可变构建器。
         *
         * @param stocksId        股票ID
         * @param stocksShortname 股票简称
         * @return 构建器实例
         */
        public static Builder builder(Integer stocksId, String stocksShortname) {
            return new Builder(stocksId, stocksShortname);
        }

        /**
         * SignalEvaluation 可变构建器,逐步填充字段后构建不可变 record。
         */
        public static class Builder {
            private final Integer stocksId;
            private final String stocksShortname;
            private StockBuyStrategy primaryStrategy;
            private List<StockBuyStrategy> matchedStrategies;
            private BigDecimal qualityScore;
            private boolean currentMatches;
            private boolean edgeTriggered;
            private BuyContext context;
            private TornStockSignalStateDO signalState;
            private TornStockMonthlyStateDO monthlyState;
            private EligibilityResult eligibilityResult;
            private Integer candidateRank;
            private boolean acceptedFormal;

            private Builder(Integer stocksId, String stocksShortname) {
                this.stocksId = stocksId;
                this.stocksShortname = stocksShortname;
            }

            /**
             * 设置主策略。
             *
             * @param primaryStrategy 主策略
             * @return 当前构建器
             */
            public Builder primaryStrategy(StockBuyStrategy primaryStrategy) {
                this.primaryStrategy = primaryStrategy;
                return this;
            }

            /**
             * 设置全部命中策略列表。
             *
             * @param matchedStrategies 命中策略列表
             * @return 当前构建器
             */
            public Builder matchedStrategies(List<StockBuyStrategy> matchedStrategies) {
                this.matchedStrategies = matchedStrategies;
                return this;
            }

            /**
             * 设置主策略质量分。
             *
             * @param qualityScore 质量分
             * @return 当前构建器
             */
            public Builder qualityScore(BigDecimal qualityScore) {
                this.qualityScore = qualityScore;
                return this;
            }

            /**
             * 设置本轮是否命中任何策略。
             *
             * @param currentMatches 是否命中
             * @return 当前构建器
             */
            public Builder currentMatches(boolean currentMatches) {
                this.currentMatches = currentMatches;
                return this;
            }

            /**
             * 设置是否为边沿触发。
             *
             * @param edgeTriggered 是否边沿触发
             * @return 当前构建器
             */
            public Builder edgeTriggered(boolean edgeTriggered) {
                this.edgeTriggered = edgeTriggered;
                return this;
            }

            /**
             * 设置买入上下文。
             *
             * @param context 买入上下文
             * @return 当前构建器
             */
            public Builder context(BuyContext context) {
                this.context = context;
                return this;
            }

            /**
             * 设置信号状态记录。
             *
             * @param signalState 信号状态
             * @return 当前构建器
             */
            public Builder signalState(TornStockSignalStateDO signalState) {
                this.signalState = signalState;
                return this;
            }

            /**
             * 设置月度状态记录。
             *
             * @param monthlyState 月度状态
             * @return 当前构建器
             */
            public Builder monthlyState(TornStockMonthlyStateDO monthlyState) {
                this.monthlyState = monthlyState;
                return this;
            }

            /**
             * 设置资格判定结果。
             *
             * @param eligibilityResult 资格结果
             * @return 当前构建器
             */
            public Builder eligibilityResult(EligibilityResult eligibilityResult) {
                this.eligibilityResult = eligibilityResult;
                return this;
            }

            /**
             * 设置候选排名。
             *
             * @param candidateRank 候选排名
             * @return 当前构建器
             */
            public Builder candidateRank(Integer candidateRank) {
                this.candidateRank = candidateRank;
                return this;
            }

            /**
             * 设置是否被正式接纳。
             *
             * @param acceptedFormal 是否正式接纳
             * @return 当前构建器
             */
            public Builder acceptedFormal(boolean acceptedFormal) {
                this.acceptedFormal = acceptedFormal;
                return this;
            }

            /**
             * 构建不可变的 SignalEvaluation。
             *
             * @return SignalEvaluation 实例
             */
            public SignalEvaluation build() {
                return new SignalEvaluation(
                        stocksId, stocksShortname, primaryStrategy, matchedStrategies,
                        qualityScore, currentMatches, edgeTriggered, context,
                        signalState, monthlyState, eligibilityResult, candidateRank, acceptedFormal
                );
            }
        }
    }

    /**
     * 策略匹配结果。
     *
     * @param primaryStrategy   主策略(质量分最高);无命中时为 null
     * @param matchedStrategies 全部命中策略列表
     * @param bestScore         最优质量分;无命中时为 null
     */
    private record StrategyMatchResult(
            StockBuyStrategy primaryStrategy,
            List<StockBuyStrategy> matchedStrategies,
            BigDecimal bestScore
    ) {
    }

    /**
     * 正式批次构建上下文 - 封装 {@link #createFormalBatch} 的 7 个参数,避免超过 Sonar 方法参数上限。
     *
     * @param candidate            候选信息
     * @param slot                 分配的槽位
     * @param bar                  本轮bar
     * @param signalReferencePrice 信号参考价
     * @param quantity             计划买入股数
     * @param roundTime            本轮时间
     * @param rank                 候选排名
     */
    private record FormalBatchContext(
            CandidateInfo candidate,
            TornStockPortfolioSlotDO slot,
            TornStockMarketBar15mDO bar,
            BigDecimal signalReferencePrice,
            Long quantity,
            LocalDateTime roundTime,
            int rank
    ) {
    }

    /**
     * 风格枚举包装器 - 封装从月度状态解析出的风格、成熟度、风险等级。
     *
     * @param style     策略适配风格
     * @param maturity  成熟度
     * @param riskLevel 风险等级
     */
    private record StockStrategyFitEnumWrapper(
            StockStrategyFitEnum style,
            StockMaturityEnum maturity,
            StockRiskLevelEnum riskLevel
    ) {
    }
}
