package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockBatchMarkDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBuySignalEvaluator.BuySignalResult;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEntrySettlementService.EntrySettlementResult;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.StockCandidateRankingPolicy;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 股票轮次事务服务 - 短事务内编排12步组合决策流程
 * <p>
 * 本类为纯编排入口,按技术方案10.3节的12步固定顺序调用各步骤处理器,
 * 保证原子性。NapCat消息投递不进入本事务。
 *
 * <h3>12步执行顺序</h3>
 * <ol>
 *   <li>创建/锁定轮次记录,状态置为PROCESSING</li>
 *   <li>处理上一轮待买入批次(成交/取消/过期)</li>
 *   <li>处理上一轮待卖出批次(成交并释放槽位)</li>
 *   <li>更新开放批次峰谷、MFE/MAE、回撤与逐轮mark</li>
 *   <li>评估开放批次退出条件,命中则置为EXIT_PENDING</li>
 *   <li>评估本轮买入信号(false->true边沿)与资格</li>
 *   <li>按qualityScore DESC排序候选并预留槽位</li>
 *   <li>写入原始信号事件、无限资金影子与拒绝观察批次</li>
 *   <li>为已成交买入/卖出写入PENDING通知审计</li>
 *   <li>更新信号边沿状态</li>
 *   <li>更新轮次为COMPLETED</li>
 *   <li>事务提交</li>
 * </ol>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRoundTransactionService {

    /**
     * 买入规则版本
     */
    public static final String BUY_RULE_VERSION = "1.0.0";
    /**
     * 卖出规则版本
     */
    public static final String SELL_RULE_VERSION = "1.0.0";
    /**
     * 仓位分配规则版本
     */
    public static final String ALLOCATION_RULE_VERSION = "1.0.0";
    /**
     * 消息通知规则版本
     */
    public static final String MESSAGE_RULE_VERSION = "1.0.0";
    /**
     * 风格分类规则版本
     */
    public static final String STYLE_RULE_VERSION = "1.0.0";
    /**
     * 风险分级规则版本
     */
    public static final String RISK_RULE_VERSION = "1.0.0";

    private final TornStockMarketRoundDAO marketRoundDao;
    private final TornStockVirtualBatchDAO virtualBatchDao;
    private final TornStockPortfolioSlotDAO portfolioSlotDao;
    private final TornStockBatchMarkDAO batchMarkDao;

    private final StockEntrySettlementService entrySettlementService;
    private final StockBatchPathService batchPathService;
    private final StockBuySignalEvaluator buySignalEvaluator;
    private final StockCandidateRankingPolicy candidateRankingPolicy;
    private final StockShadowRecordWriter shadowRecordWriter;
    private final StockSignalStateUpdater signalStateUpdater;

    /**
     * 执行一轮组合决策的全部写操作。
     * <p>
     * 在单个数据库事务内按12步固定顺序完成待成交处理、路径更新、状态流转、
     * 槽位分配与通知审计写入。传入的{@link RoundSnapshot}在事务外已批量加载,
     * 事务内不再产生N+1查询。
     *
     * @param roundTime 本轮bar开始时间(决策锚点)
     * @param snapshot  事务外已加载的批量数据快照
     */
    @Transactional(rollbackFor = Exception.class)
    public void executeRound(LocalDateTime roundTime, RoundSnapshot snapshot) {
        Objects.requireNonNull(roundTime, "轮次时间不能为空");
        Objects.requireNonNull(snapshot, "轮次快照不能为空");
        log.info("轮次事务开始: roundTime={}", roundTime);

        // 步骤1: 创建/锁定轮次记录
        TornStockMarketRoundDO round = lockOrCreateRound(roundTime, snapshot);

        // 预构建索引
        Map<Integer, TornStockMarketBar15mDO> barByStock = indexBarsByStockId(snapshot.bars());
        Map<Integer, TornStockStrategyFeature15mDO> featureByStock = indexFeaturesByStockId(snapshot.features());
        Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock = indexMonthlyStatesByStockId(snapshot.monthlyStates());
        Map<Integer, TornStockSignalStateDO> signalStateByStock = indexSignalStatesByStockId(snapshot.signalStates());

        // 步骤2: 处理待买入批次(ENTRY_PENDING)
        EntrySettlementResult entryResult = entrySettlementService.processEntryPending(
                snapshot, barByStock, roundTime);

        // 步骤3: 处理待卖出批次(EXIT_PENDING)
        List<TornStockVirtualBatchDO> exitFilledBatches = entrySettlementService.processExitPending(
                snapshot, barByStock, roundTime);

        // 步骤4: 更新开放批次路径
        List<TornStockBatchMarkDO> marks = batchPathService.updatePaths(
                snapshot, barByStock, roundTime);

        // 步骤5: 评估退出条件
        batchPathService.evaluateExits(snapshot, barByStock, featureByStock, roundTime);

        // 步骤6: 评估买入信号与资格
        BuySignalResult signalResult = buySignalEvaluator.evaluateSignals(
                snapshot, barByStock, monthlyStateByStock, signalStateByStock, roundTime);

        // 步骤7: 排序候选并预留槽位
        List<CandidateInfo> rankedCandidates = candidateRankingPolicy.rank(signalResult.formalCandidates());
        List<TornStockVirtualBatchDO> newFormalBatches = buySignalEvaluator.acceptCandidates(
                rankedCandidates, snapshot, barByStock, monthlyStateByStock, roundTime);

        // 步骤8: 写入原始信号事件、影子批次与拒绝观察批次,并回填正式批次的signalEventId
        shadowRecordWriter.writeShadowRecords(signalResult.allEvaluations(), newFormalBatches, roundTime);

        // 步骤9: 为已成交的买入/卖出写入PENDING通知审计
        shadowRecordWriter.writeNoticeAudits(
                entryResult.filledBatches(), exitFilledBatches, roundTime);

        // 步骤10: 更新信号边沿状态
        signalStateUpdater.updateStates(
                signalResult.allEvaluations(), signalStateByStock, roundTime);

        // 批量保存变更
        batchSaveChanges(snapshot, marks, newFormalBatches);

        // 步骤11: 更新轮次为COMPLETED
        completeRound(round, snapshot);

        log.info("轮次事务完成: roundTime={}, entryFilled={}, entryCancelled={}, exitFilled={}, newFormal={}, marks={}",
                roundTime, entryResult.filledBatches().size(), entryResult.cancelledBatches().size(),
                exitFilledBatches.size(), newFormalBatches.size(), marks.size());
    }

    /**
     * 创建或锁定本轮轮次记录,状态置为PROCESSING。
     *
     * @param roundTime 轮次时间
     * @param snapshot  轮次快照
     * @return 已锁定的轮次记录
     */
    private TornStockMarketRoundDO lockOrCreateRound(LocalDateTime roundTime, RoundSnapshot snapshot) {
        TornStockMarketRoundDO round = marketRoundDao.selectByRoundTimeForUpdate(roundTime);

        if (round == null) {
            round = buildNewRound(roundTime, snapshot);
            marketRoundDao.save(round);
            log.info("轮次记录创建: roundTime={}", roundTime);
        } else {
            validateRoundNotCompleted(round, roundTime);
            round.setRoundStatus(StockRoundStatusEnum.PROCESSING.getCode());
            round.setAttemptCount(round.getAttemptCount() == null ? 1 : round.getAttemptCount() + 1);
            round.setStartedAt(LocalDateTime.now());
            marketRoundDao.updateById(round);
            log.info("轮次记录锁定: roundTime={}, attemptCount={}", roundTime, round.getAttemptCount());
        }
        return round;
    }

    /**
     * 构建新的轮次记录。
     *
     * @param roundTime 轮次时间
     * @param snapshot  轮次快照
     * @return 未保存的轮次记录
     */
    private TornStockMarketRoundDO buildNewRound(LocalDateTime roundTime, RoundSnapshot snapshot) {
        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        round.setRoundTime(roundTime);
        round.setRoundStatus(StockRoundStatusEnum.PROCESSING.getCode());
        round.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        round.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        round.setBuyRuleVersion(BUY_RULE_VERSION);
        round.setSellRuleVersion(SELL_RULE_VERSION);
        round.setAllocationRuleVersion(ALLOCATION_RULE_VERSION);
        round.setMessageRuleVersion(MESSAGE_RULE_VERSION);
        round.setExpectedStockCount(snapshot.bars().size());
        round.setUsableStockCount(countStrategyReady(snapshot));
        round.setAttemptCount(0);
        round.setStartedAt(LocalDateTime.now());
        return round;
    }

    /**
     * 校验轮次是否已完成(已完成则抛异常)。
     *
     * @param round     轮次记录
     * @param roundTime 轮次时间
     */
    private void validateRoundNotCompleted(TornStockMarketRoundDO round, LocalDateTime roundTime) {
        if (StockRoundStatusEnum.COMPLETED.getCode().equals(round.getRoundStatus())) {
            log.warn("轮次[{}]已完成,跳过重复执行", roundTime);
            throw new IllegalStateException("轮次已完成,不允许重复执行: " + roundTime);
        }
    }

    /**
     * 统计策略就绪的股票数量。
     *
     * @param snapshot 轮次快照
     * @return 策略就绪数量
     */
    private int countStrategyReady(RoundSnapshot snapshot) {
        return (int) snapshot.features().stream()
                .filter(f -> Boolean.TRUE.equals(f.getStrategyReady()))
                .count();
    }

    /**
     * 批量保存全部变更的DO(批次、槽位、标记)。
     *
     * @param snapshot         轮次快照(含变更后的批次与槽位)
     * @param marks            生成的BatchMark列表
     * @param newFormalBatches 新建的正式批次列表
     */
    private void batchSaveChanges(RoundSnapshot snapshot, List<TornStockBatchMarkDO> marks,
                                  List<TornStockVirtualBatchDO> newFormalBatches) {
        List<TornStockVirtualBatchDO> allBatches = new ArrayList<>(snapshot.activeBatches());
        allBatches.addAll(newFormalBatches);
        if (!allBatches.isEmpty()) {
            virtualBatchDao.saveOrUpdateBatch(allBatches);
        }

        if (!snapshot.slots().isEmpty()) {
            portfolioSlotDao.updateBatchById(snapshot.slots());
        }

        if (!marks.isEmpty()) {
            batchMarkDao.saveBatch(marks);
        }
    }

    /**
     * 更新轮次为COMPLETED状态。
     *
     * @param round    轮次记录
     * @param snapshot 轮次快照
     */
    private void completeRound(TornStockMarketRoundDO round, RoundSnapshot snapshot) {
        round.setRoundStatus(StockRoundStatusEnum.COMPLETED.getCode());
        round.setCompletedAt(LocalDateTime.now());
        round.setUsableStockCount(countStrategyReady(snapshot));
        marketRoundDao.updateById(round);
    }

    /**
     * 按股票ID索引bar列表。
     *
     * @param bars bar列表
     * @return 按股票ID索引的映射
     */
    private Map<Integer, TornStockMarketBar15mDO> indexBarsByStockId(List<TornStockMarketBar15mDO> bars) {
        Map<Integer, TornStockMarketBar15mDO> map = new HashMap<>();
        if (bars != null) {
            for (TornStockMarketBar15mDO bar : bars) {
                map.put(bar.getStocksId(), bar);
            }
        }
        return map;
    }

    /**
     * 按股票ID索引特征列表。
     *
     * @param features 特征列表
     * @return 按股票ID索引的映射
     */
    private Map<Integer, TornStockStrategyFeature15mDO> indexFeaturesByStockId(
            List<TornStockStrategyFeature15mDO> features) {
        Map<Integer, TornStockStrategyFeature15mDO> map = new HashMap<>();
        if (features != null) {
            for (TornStockStrategyFeature15mDO feature : features) {
                map.put(feature.getStocksId(), feature);
            }
        }
        return map;
    }

    /**
     * 按股票ID索引月度状态列表。
     *
     * @param monthlyStates 月度状态列表
     * @return 按股票ID索引的映射
     */
    private Map<Integer, TornStockMonthlyStateDO> indexMonthlyStatesByStockId(
            List<TornStockMonthlyStateDO> monthlyStates) {
        Map<Integer, TornStockMonthlyStateDO> map = new HashMap<>();
        if (monthlyStates != null) {
            for (TornStockMonthlyStateDO state : monthlyStates) {
                map.put(state.getStocksId(), state);
            }
        }
        return map;
    }

    /**
     * 按股票ID索引信号状态列表。
     *
     * @param signalStates 信号状态列表
     * @return 按股票ID索引的映射
     */
    private Map<Integer, TornStockSignalStateDO> indexSignalStatesByStockId(
            List<TornStockSignalStateDO> signalStates) {
        Map<Integer, TornStockSignalStateDO> map = new HashMap<>();
        if (signalStates != null) {
            for (TornStockSignalStateDO state : signalStates) {
                map.put(state.getStocksId(), state);
            }
        }
        return map;
    }
}
