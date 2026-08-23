package pn.torn.goldeneye.torn.service.stocks.alert.signal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockBuySignalResult.BuySignalResult;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockBuySignalResult.SignalEvaluation;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockEligibilityService.EligibilityResult;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.StockBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.policy.CandidateInfo;

import java.time.LocalDateTime;
import java.util.*;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockRuleVersion;
import pn.torn.goldeneye.torn.service.stocks.alert.market.round.StockRoundTransactionService;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockCandidateTrackAllocationService;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowTrackRecorder;

/**
 * 股票买入信号评估器 - 纯规则评估门面,评估买入信号(false-&gt;true边沿)与资格。
 * <p>
 * 从 {@link StockRoundTransactionService} 拆分而来,消除原 732 行 Brain Method。
 * 本类为纯规则 Facade: 无DAO、无事务、无写操作,职责仅包含:
 * <ol>
 *   <li>对每支有特征数据的股票评估买入信号,收集通过资格的形式候选与全部评估结果</li>
 * </ol>
 * 组合决策写入由 {@link StockCandidateTrackAllocationService} 与
 * {@link StockShadowTrackRecorder} 消费 {@link BuySignalResult#allEvaluations()} 完成,
 * 不在本类职责范围内。规则子步骤分别委托给:
 * <ul>
 *   <li>{@link BuyContextAssembler} - 特征与月度状态组装 {@link BuyContext}</li>
 *   <li>{@link BuyStrategyMatcher} - 策略匹配、质量分与主策略tie-break</li>
 *   <li>{@link BuyEligibilityEvaluator} - 边沿、资格与绝对趋势守卫</li>
 *   <li>{@link CandidateFactory} - 信号评估到候选信息的转换</li>
 * </ul>
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockBuySignalEvaluator {

    /**
     * 买入规则版本(纯规则评估使用的信号状态复合键版本,与批次/事件写入版本一致)
     */
    private static final String BUY_RULE_VERSION = StockRuleVersion.BUY;

    private final List<StockBuyStrategy> buyStrategies;
    private final BuyContextAssembler contextAssembler;
    private final BuyStrategyMatcher strategyMatcher;
    private final BuyEligibilityEvaluator eligibilityEvaluator;
    private final CandidateFactory candidateFactory;

    // ==================== 步骤6: 评估买入信号 ====================

    /**
     * 评估本轮买入信号(false-&gt;true边沿)与资格,收集正式候选与全部评估结果。
     * <p>
     * 对每支 strategyReady 的股票:
     * <ol>
     *   <li>组装 {@link BuyContext}</li>
     *   <li>调用 {@link BuyStrategyMatcher#match} 遍历买入策略,选取主策略(质量分最高)</li>
     *   <li>按每个策略的复合键(stocksId, strategyType, buyRuleVersion)维护信号状态</li>
     *   <li>汇总策略命中结果,判断本轮是否存在 false-&gt;true 边沿</li>
     *   <li>边沿触发时调用 {@link BuyEligibilityEvaluator#checkEligibility} 并应用策略守卫</li>
     *   <li>ALLOWED 的候选加入正式候选列表</li>
     * </ol>
     * 单支股票的评估逻辑提取为 {@link #evaluateSingleStock},返回 null 表示该股票被跳过。
     *
     * @param snapshot            轮次快照
     * @param barByStock          按股票ID索引的bar映射
     * @param monthlyStateByStock 按股票ID索引的月度状态映射
     * @param signalStateByKey    按复合键(stocksId,strategyType,buyRuleVersion)索引的信号状态映射
     * @param roundTime           本轮bar开始时间(决策锚点)
     * @return 买入信号评估结果,包含正式候选列表与全部评估结果
     */
    public BuySignalResult evaluateSignals(
            RoundSnapshot snapshot,
            Map<Integer, TornStockMarketBar15mDO> barByStock,
            Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock,
            Map<StockSignalStateKey, TornStockSignalStateDO> signalStateByKey,
            LocalDateTime roundTime) {
        Objects.requireNonNull(snapshot, "轮次快照不能为空");
        Objects.requireNonNull(roundTime, "轮次时间不能为空");

        Set<Integer> activeSlotBackedStockIds = collectSlotBackedActiveStockIds(snapshot.activeBatches());
        List<CandidateInfo> formalCandidates = new ArrayList<>();
        List<SignalEvaluation> allEvaluations = new ArrayList<>();

        for (TornStockStrategyFeature15mDO feature : snapshot.features()) {
            SignalEvaluation evaluation = evaluateSingleStock(
                    feature, barByStock, monthlyStateByStock, signalStateByKey,
                    activeSlotBackedStockIds, roundTime);
            if (evaluation == null) {
                continue;
            }
            allEvaluations.add(evaluation);
            if (evaluation.acceptedFormal()) {
                formalCandidates.add(candidateFactory.build(evaluation));
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
     * 该字段表示"本轮评估后具备正式候选资格"的事实,不代表已经分配正式槽位或已创建正式批次。
     *
     * @param feature                  该股票的策略特征
     * @param barByStock               按股票ID索引的bar映射
     * @param monthlyStateByStock      按股票ID索引的月度状态映射
     * @param activeSlotBackedStockIds 已有正式或候选影子活跃批次的股票ID集合
     * @param roundTime                本轮时间
     * @return 信号评估结果;被跳过时返回 null
     */
    private SignalEvaluation evaluateSingleStock(
            TornStockStrategyFeature15mDO feature,
            Map<Integer, TornStockMarketBar15mDO> barByStock,
            Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock,
            Map<StockSignalStateKey, TornStockSignalStateDO> signalStateByKey,
            Set<Integer> activeSlotBackedStockIds,
            LocalDateTime roundTime) {
        if (!Boolean.TRUE.equals(feature.getStrategyReady())) {
            return null;
        }

        Integer stocksId = feature.getStocksId();
        TornStockMarketBar15mDO bar = barByStock.get(stocksId);
        if (!Stock15mBarBuildService.isUsable(bar)) {
            return null;
        }

        TornStockMonthlyStateDO monthlyState = monthlyStateByStock.get(stocksId);
        BuyContext context = contextAssembler.assemble(feature, monthlyState);
        if (context == null) {
            return null;
        }

        BuyStrategyMatcher.StrategyMatchResult matchResult = strategyMatcher.match(context);
        TornStockSignalStateDO signalState = eligibilityEvaluator.resolveSignalState(
                stocksId, matchResult.primaryStrategy(), signalStateByKey, BUY_RULE_VERSION);
        boolean edgeTriggered = eligibilityEvaluator.checkEdgeTriggered(
                stocksId, matchResult.matchedStrategies(), signalStateByKey, BUY_RULE_VERSION);

        SignalEvaluation.Builder builder = SignalEvaluation.builder(stocksId, context.stocksShortname())
                .evaluatedStrategies(buyStrategies)
                .primaryStrategy(matchResult.primaryStrategy())
                .matchedStrategies(matchResult.matchedStrategies())
                .qualityScore(matchResult.bestScore())
                .edgeTriggered(edgeTriggered)
                .context(context)
                .monthlyState(monthlyState);

        if (!edgeTriggered) {
            return builder.build();
        }

        boolean hasActiveSlotBackedBatch = activeSlotBackedStockIds.contains(stocksId);
        EligibilityResult eligibility = eligibilityEvaluator.checkEligibility(
                context, signalState, monthlyState, hasActiveSlotBackedBatch, roundTime);
        eligibility = eligibilityEvaluator.applyStrategyGuard(context, matchResult.primaryStrategy(), eligibility);
        builder.eligibilityResult(eligibility);

        boolean accepted = StockEligibilityResultEnum.ALLOWED == eligibility.result()
                && matchResult.primaryStrategy() != null;
        builder.acceptedFormal(accepted);

        if (accepted) {
            log.debug("买入信号通过资格: stocksId={}, strategy={}, score={}",
                    stocksId, matchResult.primaryStrategy().getStrategyType(), matchResult.bestScore());
        } else {
            log.debug("买入信号未通过资格: stocksId={}, result={}, reasons={}",
                    stocksId, eligibility.result(), eligibility.reasons());
        }
        return builder.build();
    }

    /**
     * 收集所有有正式或候选影子活跃批次的股票ID集合。
     * <p>
     * 候选影子与正式共享同股单活跃规则(二者均为槽位账本),候选影子与正式都阻塞
     * 同一股票的再次接纳,避免同股重复建立槽位批次。
     *
     * @param activeBatches 活跃批次列表
     * @return 有正式或候选影子活跃批次的股票ID集合
     */
    private Set<Integer> collectSlotBackedActiveStockIds(List<TornStockVirtualBatchDO> activeBatches) {
        Set<Integer> stockIds = new HashSet<>();
        if (activeBatches == null) {
            return stockIds;
        }
        for (TornStockVirtualBatchDO batch : activeBatches) {
            if ((StockLedgerTypeEnum.FORMAL.getCode().equals(batch.getLedgerType())
                    || StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode().equals(batch.getLedgerType()))
                    && batch.getStocksId() != null) {
                StockBatchStatusEnum status = StockBatchStatusEnum.fromCode(batch.getBatchStatus());
                if (status.isActive()) {
                    stockIds.add(batch.getStocksId());
                }
            }
        }
        return stockIds;
    }
}
