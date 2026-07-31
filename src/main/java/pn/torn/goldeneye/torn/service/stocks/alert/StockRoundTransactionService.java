package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRuleModeEnum;
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
import java.util.stream.Collectors;

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
    private final pn.torn.goldeneye.torn.manager.setting.SysSettingManager sysSettingManager;

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

        // 行锁落地: 在事务内重新锁定正式组合全部槽位(FOR UPDATE),
        // 替换Loader在事务外读取的快照槽位,保证槽位分配与状态变更的并发安全。
        List<TornStockPortfolioSlotDO> lockedSlots =
                portfolioSlotDao.selectAllByPortfolioCodeForUpdate(StockPortfolioService.PORTFOLIO_CODE);
        log.debug("槽位行锁已获取: slotCount={}", lockedSlots.size());

        // 活跃批次必须在同一事务内重新读取并加行锁,不能继续使用事务外快照。
        List<TornStockVirtualBatchDO> lockedFormalBatches =
                virtualBatchDao.selectActiveFormalBatchesForUpdate();
        List<TornStockVirtualBatchDO> lockedShadowBatches =
                virtualBatchDao.selectActiveShadowBatchesForUpdate();
        log.debug("活跃批次行锁已获取: formalCount={}, shadowCount={}",
                lockedFormalBatches.size(), lockedShadowBatches.size());

        // 预构建索引
        Map<Integer, TornStockMarketBar15mDO> barByStock = indexBarsByStockId(snapshot.bars());
        Map<Integer, TornStockStrategyFeature15mDO> featureByStock = indexFeaturesByStockId(snapshot.features());
        Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock = indexMonthlyStatesByStockId(snapshot.monthlyStates());
        Map<StockSignalStateKey, TornStockSignalStateDO> signalStateByKey = indexSignalStatesByKey(snapshot.signalStates());

        // 合并正式与影子活跃批次,按批次ID去重后统一参与入场/路径/退出处理
        List<TornStockVirtualBatchDO> allActiveBatches = mergeActiveBatches(
                lockedFormalBatches, lockedShadowBatches);
        List<TornStockVirtualBatchDO> shadowBatches = filterLedgerBatches(
                allActiveBatches, StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        RoundSnapshot mergedSnapshot = new RoundSnapshot(
                snapshot.bars(), snapshot.features(), snapshot.monthlyStates(),
                allActiveBatches, shadowBatches, snapshot.signalStates(),
                lockedSlots, snapshot.roundTime());

        // 步骤2: 处理待买入批次(ENTRY_PENDING) - 含正式与影子
        EntrySettlementResult entryResult = entrySettlementService.processEntryPending(
                mergedSnapshot, barByStock, roundTime);

        // 步骤3: 处理待卖出批次(EXIT_PENDING) - 含正式与影子
        List<TornStockVirtualBatchDO> exitFilledBatches = entrySettlementService.processExitPending(
                mergedSnapshot, barByStock, roundTime);

        // 步骤4: 更新开放批次路径 - 含正式与影子
        List<TornStockBatchMarkDO> marks = batchPathService.updatePaths(
                mergedSnapshot, barByStock, roundTime);

        // 步骤5: 评估退出条件 - 含正式与影子
        batchPathService.evaluateExits(mergedSnapshot, barByStock, featureByStock, roundTime);

        // 步骤6: 评估买入信号与资格
        BuySignalResult signalResult = buySignalEvaluator.evaluateSignals(
                snapshot, barByStock, monthlyStateByStock, signalStateByKey, roundTime);

        // 步骤7: 读取规则模式后再进行候选编排
        StockRuleModeEnum ruleMode = resolveRuleMode();
        List<CandidateInfo> rankedCandidates = ruleMode == StockRuleModeEnum.OFF
                ? List.of()
                : candidateRankingPolicy.rank(signalResult.formalCandidates());
        rankedCandidates = StockRoundExitGuard.excludeFormalExitStocks(rankedCandidates, exitFilledBatches);
        Map<Integer, StockBuySignalEvaluator.SignalEvaluation> evaluationByStockId = ruleMode == StockRuleModeEnum.OFF
                ? Map.of()
                : signalResult.allEvaluations().stream()
                .filter(Objects::nonNull)
                .filter(evaluation -> evaluation.stocksId() != null)
                .collect(Collectors.toMap(StockBuySignalEvaluator.SignalEvaluation::stocksId,
                        evaluation -> evaluation, (left, right) -> left));
        StockCandidateAllocationResult allocationResult = StockCandidateAllocationResult.empty();
        if (ruleMode == StockRuleModeEnum.PROVISIONAL || ruleMode == StockRuleModeEnum.FORMAL) {
            allocationResult = buySignalEvaluator.acceptCandidates(
                    rankedCandidates, mergedSnapshot, barByStock, monthlyStateByStock,
                    evaluationByStockId, roundTime);
        } else {
            log.info("规则模式[{}]不创建正式批次,跳过候选接纳: candidateCount={}",
                    ruleMode.getCode(), rankedCandidates.size());
        }

        // 构建候选排名映射(stocksId -> rank),供事件回写
        Map<Integer, Integer> candidateRankByStockId = ruleMode == StockRuleModeEnum.OFF
                ? Map.of()
                : buildCandidateRankByStockId(rankedCandidates);

        // 步骤8: OFF模式不写入买入研究事件和Shadow批次
        if (ruleMode != StockRuleModeEnum.OFF) {
            shadowRecordWriter.writeShadowRecords(signalResult.allEvaluations(),
                    allocationResult.formalBatches(), candidateRankByStockId,
                    allocationResult.resultByStockId(), roundTime);
        } else {
            log.info("规则模式OFF,跳过信号事件和影子批次写入");
        }

        // 步骤9: 为已成交的买入/卖出写入PENDING通知审计
        shadowRecordWriter.writeNoticeAudits(
                entryResult.filledBatches(), exitFilledBatches, roundTime);

        // 步骤10: OFF模式不推进新的买入信号边沿状态,避免重新启用时丢失边沿
        if (ruleMode != StockRuleModeEnum.OFF) {
            signalStateUpdater.updateStates(
                    signalResult.allEvaluations(), signalStateByKey, roundTime);
        } else {
            log.info("规则模式OFF,跳过买入信号状态更新");
        }
        signalStateUpdater.updateCloseStates(exitFilledBatches, signalStateByKey);

        // 批量保存变更(含影子批次的路径/状态变更)
        batchSaveChanges(mergedSnapshot, marks);

        // 步骤11: 更新轮次为COMPLETED
        completeRound(round, mergedSnapshot);

        log.info("轮次事务完成: roundTime={}, entryFilled={}, entryCancelled={}, exitFilled={}, newFormal={}, marks={}",
                roundTime, entryResult.filledBatches().size(), entryResult.cancelledBatches().size(),
                exitFilledBatches.size(), allocationResult.formalBatches().size(), marks.size());
    }

    /**
     * 构建候选排名索引。
     *
     * @param rankedCandidates 已排序候选
     * @return 股票ID到候选排名的映射
     */
    private Map<Integer, Integer> buildCandidateRankByStockId(List<CandidateInfo> rankedCandidates) {
        Map<Integer, Integer> rankByStockId = new HashMap<>();
        if (rankedCandidates == null) {
            return rankByStockId;
        }
        for (int i = 0; i < rankedCandidates.size(); i++) {
            CandidateInfo candidate = rankedCandidates.get(i);
            if (candidate != null && candidate.stocksId() != null) {
                rankByStockId.putIfAbsent(candidate.stocksId(), i + 1);
            }
        }
        return rankByStockId;
    }

    /**
     * 合并正式与Shadow活跃批次,按主键去重。
     *
     * @param formalBatches 正式活跃批次
     * @param shadowBatches Shadow活跃批次
     * @return 去重后的活跃批次
     */
    private List<TornStockVirtualBatchDO> mergeActiveBatches(
            List<TornStockVirtualBatchDO> formalBatches,
            List<TornStockVirtualBatchDO> shadowBatches) {
        Map<Long, TornStockVirtualBatchDO> batchesById = new LinkedHashMap<>();
        addBatchesById(batchesById, formalBatches);
        addBatchesById(batchesById, shadowBatches);
        return new ArrayList<>(batchesById.values());
    }

    /**
     * 将批次加入主键索引;无主键对象不参与合并。
     *
     * @param batchesById 批次索引
     * @param batches     待加入批次
     */
    private void addBatchesById(Map<Long, TornStockVirtualBatchDO> batchesById,
                                List<TornStockVirtualBatchDO> batches) {
        if (batches == null) {
            return;
        }
        for (TornStockVirtualBatchDO batch : batches) {
            if (batch != null && batch.getId() != null) {
                batchesById.putIfAbsent(batch.getId(), batch);
            }
        }
    }

    /**
     * 按账本类型过滤批次。
     *
     * @param batches    批次列表
     * @param ledgerType 账本类型
     * @return 指定账本类型批次
     */
    private List<TornStockVirtualBatchDO> filterLedgerBatches(
            List<TornStockVirtualBatchDO> batches, String ledgerType) {
        if (batches == null || batches.isEmpty()) {
            return List.of();
        }
        return batches.stream()
                .filter(Objects::nonNull)
                .filter(batch -> ledgerType.equals(batch.getLedgerType()))
                .toList();
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
     * <p>
     * mergedSnapshot.activeBatches已包含正式和Shadow活跃批次,本方法再次按批次ID去重后保存。
     *
     * @param snapshot 轮次快照(含变更后的正式与Shadow批次、槽位)
     * @param marks    生成的BatchMark列表
     */
    private void batchSaveChanges(RoundSnapshot snapshot, List<TornStockBatchMarkDO> marks) {
        List<TornStockVirtualBatchDO> allBatches = mergeActiveBatches(snapshot.activeBatches(), null);
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
     * 按复合键(stocksId, strategyType, buyRuleVersion)索引信号状态列表。
     * <p>
     * 替代原按单股票ID索引的方式,避免同股多策略的信号状态互相覆盖。
     * 对应数据库唯一索引 uk_stock_signal_state_stock_strat_ver。
     *
     * @param signalStates 信号状态列表
     * @return 按复合键索引的映射
     */
    private Map<StockSignalStateKey, TornStockSignalStateDO> indexSignalStatesByKey(
            List<TornStockSignalStateDO> signalStates) {
        Map<StockSignalStateKey, TornStockSignalStateDO> map = new HashMap<>();
        if (signalStates != null) {
            for (TornStockSignalStateDO state : signalStates) {
                StockSignalStateKey key = StockSignalStateKey.of(state);
                if (key != null) {
                    map.put(key, state);
                }
            }
        }
        return map;
    }

    /**
     * 从系统配置读取当前规则模式。
     * <p>
     * 配置缺失或解析失败时默认返回SHADOW(安全降级,只写研究不创建正式批次)。OFF模式由轮次编排层跳过买入研究写入与信号状态推进。
     *
     * @return 当前规则模式
     */
    private StockRuleModeEnum resolveRuleMode() {
        String modeCode = sysSettingManager.getSettingValue(
                pn.torn.goldeneye.constants.torn.SettingConstants.KEY_VIP_STOCK_RULE_MODE);
        if (modeCode == null || modeCode.isBlank()) {
            return StockRuleModeEnum.SHADOW;
        }
        try {
            return StockRuleModeEnum.fromCode(modeCode);
        } catch (IllegalArgumentException e) {
            log.warn("规则模式编码无效,默认SHADOW: code={}", modeCode);
            return StockRuleModeEnum.SHADOW;
        }
    }
}
