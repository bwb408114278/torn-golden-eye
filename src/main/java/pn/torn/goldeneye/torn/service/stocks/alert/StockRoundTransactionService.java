package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCancelReasonEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCloseTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockNoticeTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockPortfolioDecisionEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockBatchMarkDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalStateDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockBatchMarkDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBatchExitService.ExitEvaluation;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService.EligibilityResult;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.StockShadowService.StockSignalEventContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.StockCandidateRankingPolicy;
import pn.torn.goldeneye.utils.JsonUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 股票轮次事务服务 - 短事务内完成待成交、路径更新、状态流转、槽位和通知审计写入
 * <p>
 * 按 {@value #SELL_RULE_VERSION_TEXT} 技术方案10.3节的12步固定顺序在单个数据库事务内执行
 * 本轮组合决策的全部写操作,保证原子性。NapCat消息投递不进入本事务,由下游通知调度器
 * 消费PENDING状态的通知审计记录异步发送。
 *
 * <h3>12步执行顺序</h3>
 * <ol>
 *   <li>创建/锁定轮次记录,状态置为PROCESSING</li>
 *   <li>处理上一轮待买入批次(成交/取消/过期)</li>
 *   <li>处理上一轮待卖出批次(成交并释放槽位)</li>
 *   <li>更新开放批次峰谷、MFE/MAE、回撤与逐轮mark</li>
 *   <li>评估开放批次退出条件,命中则置为EXIT_PENDING</li>
 *   <li>评估本轮买入信号(false-&gt;true边沿)与资格</li>
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
     * 技术方案版本文本(仅用于Javadoc展示)
     */
    static final String SELL_RULE_VERSION_TEXT = "1.2.12";
    /**
     * 通知编号前缀
     */
    private static final String NOTICE_NO_PREFIX = "N";
    /**
     * 通知编号时间戳格式
     */
    private static final String NOTICE_NO_TIMESTAMP_PATTERN = "yyyyMMddHHmmssSSS";
    /**
     * 正式决策-持有
     */
    private static final String FORMAL_DECISION_HOLD = "HOLD";
    /**
     * 正式决策-卖出
     */
    private static final String FORMAL_DECISION_SELL = "SELL";
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
     * BigDecimal运算精度
     */
    private static final int MATH_SCALE = 18;
    /**
     * 通知编号格式化器
     */
    private static final java.time.format.DateTimeFormatter NOTICE_NO_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern(NOTICE_NO_TIMESTAMP_PATTERN);

    private final TornStockMarketRoundDAO marketRoundDAO;
    private final TornStockVirtualBatchDAO virtualBatchDAO;
    private final TornStockPortfolioSlotDAO portfolioSlotDAO;
    private final TornStockSignalStateDAO signalStateDAO;
    private final TornStockSignalEventDAO signalEventDAO;
    private final TornStockBatchMarkDAO batchMarkDAO;
    private final TornStockNoticeAuditDAO noticeAuditDAO;

    private final StockPortfolioService portfolioService;
    private final StockBatchExitService batchExitService;
    private final StockEligibilityService eligibilityService;
    private final StockCandidateRankingPolicy candidateRankingPolicy;
    private final StockShadowService shadowService;
    private final List<StockBuyStrategy> buyStrategies;

    // ==================== 核心入口 ====================

    /**
     * 执行一轮组合决策的全部写操作。
     * <p>
     * 在单个数据库事务内按12步固定顺序完成待成交处理、路径更新、状态流转、
     * 槽位分配与通知审计写入。传入的{@link RoundSnapshot}在事务外已批量加载,
     * 事务内不再产生N+1查询。NapCat消息投递不进入本事务。
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

        // 预构建索引: stocksId -> bar, stocksId -> feature, stocksId -> monthlyState
        Map<Integer, TornStockMarketBar15mDO> barByStock = indexBarsByStock(snapshot.bars());
        Map<Integer, TornStockStrategyFeature15mDO> featureByStock = indexFeaturesByStock(snapshot.features());
        Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock = indexMonthlyStatesByStock(snapshot.monthlyStates());
        Map<Integer, TornStockSignalStateDO> signalStateByStock = indexSignalStatesByStock(snapshot.signalStates());

        // 步骤2: 处理待买入批次(ENTRY_PENDING)
        List<TornStockVirtualBatchDO> entryFilledBatches = new ArrayList<>();
        List<TornStockVirtualBatchDO> entryCancelledBatches = new ArrayList<>();
        processEntryPendingBatches(snapshot, barByStock, roundTime, entryFilledBatches, entryCancelledBatches);

        // 步骤3: 处理待卖出批次(EXIT_PENDING)
        List<TornStockVirtualBatchDO> exitFilledBatches = new ArrayList<>();
        processExitPendingBatches(snapshot, barByStock, roundTime, exitFilledBatches);

        // 步骤4: 更新开放批次路径(peak/trough/mfe/mae/peakDrawdown)并写入BatchMark
        List<TornStockBatchMarkDO> marks = new ArrayList<>();
        updateOpenBatchPaths(snapshot, barByStock, featureByStock, roundTime, marks);

        // 步骤5: 评估退出条件,命中则置为EXIT_PENDING
        evaluateExits(snapshot, barByStock, featureByStock, roundTime);

        // 步骤6: 评估买入信号与资格
        List<CandidateInfo> formalCandidates = new ArrayList<>();
        List<SignalEvaluation> allEvaluations = new ArrayList<>();
        evaluateBuySignals(snapshot, barByStock, featureByStock, monthlyStateByStock,
                signalStateByStock, roundTime, formalCandidates, allEvaluations);

        // 步骤7: 排序候选并预留槽位
        List<CandidateInfo> rankedCandidates = candidateRankingPolicy.rank(formalCandidates);
        List<TornStockVirtualBatchDO> newFormalBatches = new ArrayList<>();
        acceptFormalCandidates(rankedCandidates, snapshot, barByStock, roundTime, newFormalBatches);

        // 步骤8: 写入原始信号事件、影子批次与拒绝观察批次
        writeShadowRecords(allEvaluations, roundTime);

        // 步骤9: 为已成交的买入/卖出写入PENDING通知审计
        writeNoticeAudits(entryFilledBatches, exitFilledBatches, newFormalBatches, roundTime);

        // 步骤10: 更新信号边沿状态
        updateSignalStates(allEvaluations, signalStateByStock, roundTime);

        // 批量保存变更
        batchSaveChanges(snapshot, marks);

        // 步骤11: 更新轮次为COMPLETED
        completeRound(round, snapshot);

        log.info("轮次事务完成: roundTime={}, entryFilled={}, entryCancelled={}, exitFilled={}, newFormal={}, marks={}",
                roundTime, entryFilledBatches.size(), entryCancelledBatches.size(),
                exitFilledBatches.size(), newFormalBatches.size(), marks.size());
    }

    // ==================== 步骤1: 锁定轮次记录 ====================

    /**
     * 创建或锁定本轮轮次记录,状态置为PROCESSING。
     * <p>
     * 查询roundTime对应的轮次记录:若不存在则创建新记录(PENDING-&gt;PROCESSING),
     * 若已存在且未完成则置为PROCESSING,若已完成则跳过(幂等)。
     *
     * @param roundTime 轮次时间
     * @param snapshot  轮次快照
     * @return 已锁定的轮次记录
     */
    private TornStockMarketRoundDO lockOrCreateRound(LocalDateTime roundTime, RoundSnapshot snapshot) {
        TornStockMarketRoundDO round = marketRoundDAO.lambdaQuery()
                .eq(TornStockMarketRoundDO::getRoundTime, roundTime)
                .one();

        if (round == null) {
            round = new TornStockMarketRoundDO();
            round.setRoundTime(roundTime);
            round.setRoundStatus(StockRoundStatusEnum.PROCESSING.getCode());
            round.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
            round.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
            round.setBuyRuleVersion(BUY_RULE_VERSION);
            round.setSellRuleVersion(SELL_RULE_VERSION);
            round.setAllocationRuleVersion(ALLOCATION_RULE_VERSION);
            round.setMessageRuleVersion(MESSAGE_RULE_VERSION);
            round.setExpectedStockCount(snapshot.bars().size());
            round.setUsableStockCount((int) snapshot.features().stream()
                    .filter(f -> Boolean.TRUE.equals(f.getStrategyReady()))
                    .count());
            round.setAttemptCount(0);
            round.setStartedAt(LocalDateTime.now());
            marketRoundDAO.save(round);
            log.info("轮次记录创建: roundTime={}", roundTime);
        } else {
            String currentStatus = round.getRoundStatus();
            if (StockRoundStatusEnum.COMPLETED.getCode().equals(currentStatus)) {
                log.warn("轮次[{}]已完成,跳过重复执行", roundTime);
                throw new IllegalStateException("轮次已完成,不允许重复执行: " + roundTime);
            }
            round.setRoundStatus(StockRoundStatusEnum.PROCESSING.getCode());
            round.setAttemptCount(round.getAttemptCount() == null ? 1 : round.getAttemptCount() + 1);
            round.setStartedAt(LocalDateTime.now());
            marketRoundDAO.updateById(round);
            log.info("轮次记录锁定: roundTime={}, attemptCount={}", roundTime, round.getAttemptCount());
        }
        return round;
    }

    // ==================== 步骤2: 处理待买入批次 ====================

    /**
     * 处理ENTRY_PENDING批次:检查本轮bar是否为紧邻下一连续bar,成交或取消。
     * <p>
     * 处理规则:
     * <ul>
     *   <li>本轮bar与预期入场bar连续且价格偏离通过 -&gt; 成交(状态OPEN)</li>
     *   <li>本轮bar连续但价格偏离超限 -&gt; 取消(CANCELLED, reason=ENTRY_PRICE_DEVIATION)</li>
     *   <li>超过entryStaleAt仍未成交 -&gt; 取消(CANCELLED, reason=ENTRY_DATA_STALE)</li>
     *   <li>本轮bar不可用或非连续 -&gt; 保持ENTRY_PENDING等待下一轮</li>
     * </ul>
     *
     * @param snapshot             轮次快照
     * @param barByStock           按股票ID索引的bar映射
     * @param roundTime            本轮时间
     * @param entryFilledBatches   输出: 已成交的买入批次列表
     * @param entryCancelledBatches 输出: 已取消的买入批次列表
     */
    private void processEntryPendingBatches(RoundSnapshot snapshot,
                                             Map<Integer, TornStockMarketBar15mDO> barByStock,
                                             LocalDateTime roundTime,
                                             List<TornStockVirtualBatchDO> entryFilledBatches,
                                             List<TornStockVirtualBatchDO> entryCancelledBatches) {
        List<TornStockVirtualBatchDO> entryPendingBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.ENTRY_PENDING.getCode().equals(batch.getBatchStatus()))
                .toList();

        if (entryPendingBatches.isEmpty()) {
            log.debug("无待买入批次需要处理");
            return;
        }

        Map<Long, TornStockPortfolioSlotDO> slotById = indexSlotsById(snapshot.slots());

        for (TornStockVirtualBatchDO batch : entryPendingBatches) {
            TornStockMarketBar15mDO currentBar = barByStock.get(batch.getStocksId());

            // 检查是否超过过期时间
            if (batch.getEntryStaleAt() != null && roundTime.isAfter(batch.getEntryStaleAt())) {
                cancelEntryBatch(batch, slotById, StockCancelReasonEnum.ENTRY_DATA_STALE, entryCancelledBatches);
                log.info("待买入批次过期取消: batchNo={}, stocksId={}, staleAt={}, roundTime={}",
                        batch.getBatchNo(), batch.getStocksId(), batch.getEntryStaleAt(), roundTime);
                continue;
            }

            // 检查本轮bar是否可用
            if (currentBar == null || !Stock15mBarBuildService.isUsable(currentBar)) {
                log.debug("待买入批次[{}]本轮bar不可用,继续等待: stocksId={}",
                        batch.getBatchNo(), batch.getStocksId());
                continue;
            }

            // 检查bar连续性: 本轮bar的barStartTime应等于预期入场bar时间
            boolean consecutive = batch.getExpectedEntryBarTime() != null
                    && currentBar.getBarStartTime() != null
                    && currentBar.getBarStartTime().equals(batch.getExpectedEntryBarTime());
            if (!consecutive) {
                log.debug("待买入批次[{}]本轮bar非连续,继续等待: expected={}, actual={}",
                        batch.getBatchNo(), batch.getExpectedEntryBarTime(),
                        currentBar.getBarStartTime());
                continue;
            }

            BigDecimal entryReferencePrice = currentBar.getLastPrice();

            // 检查价格偏离
            if (StockPortfolioService.checkEntryPriceDeviation(batch.getSignalReferencePrice(), entryReferencePrice)) {
                cancelEntryBatch(batch, slotById, StockCancelReasonEnum.ENTRY_PRICE_DEVIATION, entryCancelledBatches);
                log.info("待买入批次价格偏离取消: batchNo={}, signalPrice={}, entryPrice={}",
                        batch.getBatchNo(), batch.getSignalReferencePrice(), entryReferencePrice);
                continue;
            }

            // 成交: 状态OPEN
            fillEntryBatch(batch, currentBar, slotById, roundTime, entryFilledBatches);
            log.info("待买入批次成交: batchNo={}, stocksId={}, entryPrice={}",
                    batch.getBatchNo(), batch.getStocksId(), entryReferencePrice);
        }
    }

    /**
     * 成交待买入批次:状态置为OPEN,设置入场参考价、入场时间、股数,占用槽位。
     *
     * @param batch             待成交批次
     * @param currentBar        本轮bar
     * @param slotById          槽位ID索引映射
     * @param roundTime         本轮时间
     * @param entryFilledBatches 输出: 已成交批次列表
     */
    private void fillEntryBatch(TornStockVirtualBatchDO batch, TornStockMarketBar15mDO currentBar,
                                 Map<Long, TornStockPortfolioSlotDO> slotById,
                                 LocalDateTime roundTime,
                                 List<TornStockVirtualBatchDO> entryFilledBatches) {
        BigDecimal entryReferencePrice = currentBar.getLastPrice();
        TornStockPortfolioSlotDO slot = batch.getSlotId() != null ? slotById.get(batch.getSlotId()) : null;

        Long quantity = 0L;
        BigDecimal investedCash = BigDecimal.ZERO;
        BigDecimal remainingCash = BigDecimal.ZERO;

        if (slot != null) {
            quantity = StockPortfolioService.calculateQuantity(slot.getAvailableCash(), entryReferencePrice);
            if (quantity > 0) {
                investedCash = entryReferencePrice.multiply(BigDecimal.valueOf(quantity));
                remainingCash = slot.getAvailableCash().subtract(investedCash);
                portfolioService.occupySlot(slot, quantity, entryReferencePrice, batch.getId());
            }
        }

        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        batch.setEntryReferencePrice(entryReferencePrice);
        batch.setEntryTime(roundTime);
        batch.setQuantity(quantity);
        batch.setInvestedCash(investedCash);
        batch.setRemainingCash(remainingCash);
        batch.setPeakPrice(entryReferencePrice);
        batch.setTroughPrice(entryReferencePrice);
        batch.setCurrentNetReturn(BigDecimal.ZERO);
        batch.setMfe(BigDecimal.ZERO);
        batch.setMae(BigDecimal.ZERO);
        batch.setPeakDrawdown(BigDecimal.ZERO);
        batch.setBuyRuleVersion(BUY_RULE_VERSION);
        batch.setSellRuleVersion(SELL_RULE_VERSION);
        batch.setAllocationRuleVersion(ALLOCATION_RULE_VERSION);
        batch.setMessageRuleVersion(MESSAGE_RULE_VERSION);

        entryFilledBatches.add(batch);
    }

    /**
     * 取消待买入批次:状态置为CANCELLED,释放槽位。
     *
     * @param batch             待取消批次
     * @param slotById          槽位ID索引映射
     * @param reason            取消原因
     * @param entryCancelledBatches 输出: 已取消批次列表
     */
    private void cancelEntryBatch(TornStockVirtualBatchDO batch,
                                   Map<Long, TornStockPortfolioSlotDO> slotById,
                                   StockCancelReasonEnum reason,
                                   List<TornStockVirtualBatchDO> entryCancelledBatches) {
        if (batch.getSlotId() != null) {
            TornStockPortfolioSlotDO slot = slotById.get(batch.getSlotId());
            if (slot != null) {
                portfolioService.releaseSlot(slot);
            }
        }
        batch.setBatchStatus(StockBatchStatusEnum.CANCELLED.getCode());
        batch.setCancelReason(reason.getCode());
        entryCancelledBatches.add(batch);
    }

    // ==================== 步骤3: 处理待卖出批次 ====================

    /**
     * 处理EXIT_PENDING批次:检查本轮bar是否为紧邻下一连续bar,成交则关闭批次并释放槽位。
     * <p>
     * 处理规则:
     * <ul>
     *   <li>本轮bar与预期平仓bar连续 -&gt; 成交(状态CLOSED_xxx,设置exitReferencePrice/exitTime/netReturn)</li>
     *   <li>本轮bar不可用或非连续 -&gt; 保持EXIT_PENDING等待下一轮</li>
     * </ul>
     *
     * @param snapshot          轮次快照
     * @param barByStock        按股票ID索引的bar映射
     * @param roundTime         本轮时间
     * @param exitFilledBatches 输出: 已成交的卖出批次列表
     */
    private void processExitPendingBatches(RoundSnapshot snapshot,
                                            Map<Integer, TornStockMarketBar15mDO> barByStock,
                                            LocalDateTime roundTime,
                                            List<TornStockVirtualBatchDO> exitFilledBatches) {
        List<TornStockVirtualBatchDO> exitPendingBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.EXIT_PENDING.getCode().equals(batch.getBatchStatus()))
                .toList();

        if (exitPendingBatches.isEmpty()) {
            log.debug("无待卖出批次需要处理");
            return;
        }

        Map<Long, TornStockPortfolioSlotDO> slotById = indexSlotsById(snapshot.slots());

        for (TornStockVirtualBatchDO batch : exitPendingBatches) {
            TornStockMarketBar15mDO currentBar = barByStock.get(batch.getStocksId());

            if (currentBar == null || !Stock15mBarBuildService.isUsable(currentBar)) {
                log.debug("待卖出批次[{}]本轮bar不可用,继续等待: stocksId={}",
                        batch.getBatchNo(), batch.getStocksId());
                continue;
            }

            boolean consecutive = batch.getExpectedExitBarTime() != null
                    && currentBar.getBarStartTime() != null
                    && currentBar.getBarStartTime().equals(batch.getExpectedExitBarTime());
            if (!consecutive) {
                log.debug("待卖出批次[{}]本轮bar非连续,继续等待: expected={}, actual={}",
                        batch.getBatchNo(), batch.getExpectedExitBarTime(),
                        currentBar.getBarStartTime());
                continue;
            }

            fillExitBatch(batch, currentBar, slotById, roundTime, exitFilledBatches);
            log.info("待卖出批次成交: batchNo={}, stocksId={}, exitPrice={}, closeType={}",
                    batch.getBatchNo(), batch.getStocksId(), currentBar.getLastPrice(), batch.getExitReason());
        }
    }

    /**
     * 成交待卖出批次:状态置为CLOSED_xxx,设置卖出参考价、卖出时间、净收益,结算槽位。
     *
     * @param batch            待成交批次
     * @param currentBar       本轮bar
     * @param slotById         槽位ID索引映射
     * @param roundTime        本轮时间
     * @param exitFilledBatches 输出: 已成交批次列表
     */
    private void fillExitBatch(TornStockVirtualBatchDO batch, TornStockMarketBar15mDO currentBar,
                                Map<Long, TornStockPortfolioSlotDO> slotById,
                                LocalDateTime roundTime,
                                List<TornStockVirtualBatchDO> exitFilledBatches) {
        BigDecimal exitReferencePrice = currentBar.getLastPrice();
        Long quantity = batch.getQuantity() != null ? batch.getQuantity() : 0L;

        // 结算槽位
        if (batch.getSlotId() != null && quantity > 0) {
            TornStockPortfolioSlotDO slot = slotById.get(batch.getSlotId());
            if (slot != null) {
                portfolioService.settleSlot(slot, quantity, exitReferencePrice);
            }
        }

        // 计算净收益
        BigDecimal netReturn = StockPortfolioService.calculateNetReturn(
                batch.getEntryReferencePrice(), exitReferencePrice);
        BigDecimal sellProceeds = quantity > 0
                ? exitReferencePrice.multiply(BigDecimal.valueOf(quantity)).multiply(StockPortfolioService.SELL_FEE_RATE)
                : BigDecimal.ZERO;

        // 确定关闭状态(根据exitReason映射到CLOSED_xxx)
        String closeType = batch.getExitReason();
        StockCloseTypeEnum closeTypeEnum = closeType != null
                ? safeParseCloseType(closeType) : StockCloseTypeEnum.CLOSED_TARGET;
        StockBatchStatusEnum closeStatus = mapCloseTypeToBatchStatus(closeTypeEnum);

        batch.setBatchStatus(closeStatus.getCode());
        batch.setExitReferencePrice(exitReferencePrice);
        batch.setExitTime(roundTime);
        batch.setNetReturn(netReturn);
        batch.setSellProceeds(sellProceeds);
        batch.setSellRuleVersion(SELL_RULE_VERSION);
        batch.setMessageRuleVersion(MESSAGE_RULE_VERSION);

        // 设置冷却时间(平仓后进入冷却期)
        batch.setCooldownUntil(roundTime.plusDays(StockPortfolioService.MAX_HOLD_DAYS));

        exitFilledBatches.add(batch);
    }

    // ==================== 步骤4: 更新开放批次路径 ====================

    /**
     * 更新所有OPEN批次的持仓路径(peak/trough/mfe/mae/peakDrawdown)并写入逐轮BatchMark。
     * <p>
     * 对每个OPEN批次,用本轮bar价格更新峰值/谷值,计算MFE/MAE/回撤和当前净收益,
     * 生成BatchMark记录本轮快照。
     *
     * @param snapshot        轮次快照
     * @param barByStock      按股票ID索引的bar映射
     * @param featureByStock  按股票ID索引的特征映射
     * @param roundTime       本轮时间
     * @param marks           输出: 生成的BatchMark列表
     */
    private void updateOpenBatchPaths(RoundSnapshot snapshot,
                                       Map<Integer, TornStockMarketBar15mDO> barByStock,
                                       Map<Integer, TornStockStrategyFeature15mDO> featureByStock,
                                       LocalDateTime roundTime,
                                       List<TornStockBatchMarkDO> marks) {
        List<TornStockVirtualBatchDO> openBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus()))
                .toList();

        if (openBatches.isEmpty()) {
            log.debug("无开放批次需要更新路径");
            return;
        }

        for (TornStockVirtualBatchDO batch : openBatches) {
            TornStockMarketBar15mDO currentBar = barByStock.get(batch.getStocksId());
            if (currentBar == null || !Stock15mBarBuildService.isUsable(currentBar)) {
                log.debug("开放批次[{}]本轮bar不可用,跳过路径更新", batch.getBatchNo());
                continue;
            }

            BigDecimal currentPrice = currentBar.getLastPrice();
            BigDecimal entryPrice = batch.getEntryReferencePrice();
            if (entryPrice == null || entryPrice.signum() <= 0) {
                log.warn("开放批次[{}]入场参考价缺失,跳过路径更新", batch.getBatchNo());
                continue;
            }

            // 更新峰值/谷值
            BigDecimal currentPeak = batch.getPeakPrice() != null ? batch.getPeakPrice() : entryPrice;
            BigDecimal currentTrough = batch.getTroughPrice() != null ? batch.getTroughPrice() : entryPrice;
            BigDecimal newPeak = currentPrice.compareTo(currentPeak) > 0 ? currentPrice : currentPeak;
            BigDecimal newTrough = currentPrice.compareTo(currentTrough) < 0 ? currentPrice : currentTrough;
            batch.setPeakPrice(newPeak);
            batch.setTroughPrice(newTrough);

            // 计算MFE/MAE
            BigDecimal mfe = calculateMfe(entryPrice, newPeak);
            BigDecimal mae = calculateMae(entryPrice, newTrough);
            batch.setMfe(mfe);
            batch.setMae(mae);

            // 计算峰值回撤
            BigDecimal peakDrawdown = calculatePeakDrawdown(newPeak, newTrough);
            batch.setPeakDrawdown(peakDrawdown);

            // 计算当前净收益
            BigDecimal currentNetReturn = StockPortfolioService.calculateNetReturn(entryPrice, currentPrice);
            batch.setCurrentNetReturn(currentNetReturn);

            // 生成BatchMark
            TornStockBatchMarkDO mark = buildBatchMark(batch, currentPrice, currentNetReturn,
                    newPeak, newTrough, mfe, mae, peakDrawdown, roundTime);
            marks.add(mark);

            log.debug("开放批次路径更新: batchNo={}, price={}, peak={}, trough={}, mfe={}, mae={}, netReturn={}",
                    batch.getBatchNo(), currentPrice, newPeak, newTrough, mfe, mae, currentNetReturn);
        }
    }

    /**
     * 构建批次标记记录。
     *
     * @param batch            批次
     * @param currentPrice     本轮参考价
     * @param currentNetReturn 本轮净收益
     * @param peakPrice        峰值价格
     * @param troughPrice      谷值价格
     * @param mfe              最大有利偏移
     * @param mae              最大不利偏移
     * @param peakDrawdown     峰值回撤
     * @param roundTime        本轮时间
     * @return 批次标记DO
     */
    private TornStockBatchMarkDO buildBatchMark(TornStockVirtualBatchDO batch,
                                                 BigDecimal currentPrice,
                                                 BigDecimal currentNetReturn,
                                                 BigDecimal peakPrice,
                                                 BigDecimal troughPrice,
                                                 BigDecimal mfe,
                                                 BigDecimal mae,
                                                 BigDecimal peakDrawdown,
                                                 LocalDateTime roundTime) {
        TornStockBatchMarkDO mark = new TornStockBatchMarkDO();
        mark.setBatchId(batch.getId());
        mark.setRoundTime(roundTime);
        mark.setReferencePrice(currentPrice);
        mark.setCurrentNetReturn(currentNetReturn);
        mark.setPeakPrice(peakPrice);
        mark.setTroughPrice(troughPrice);
        mark.setMfe(mfe);
        mark.setMae(mae);
        mark.setPeakDrawdown(peakDrawdown);
        mark.setFormalDecision(FORMAL_DECISION_HOLD);
        mark.setFormalReason("持仓跟踪中");
        return mark;
    }

    // ==================== 步骤5: 评估退出条件 ====================

    /**
     * 对每个OPEN批次评估退出条件,命中则置为EXIT_PENDING。
     * <p>
     * 调用{@link StockBatchExitService#evaluateExit}评估,命中退出时设置exitSignalTime、
     * expectedExitBarTime(下一轮bar时间)和exitReason。
     *
     * @param snapshot       轮次快照
     * @param barByStock     按股票ID索引的bar映射
     * @param featureByStock 按股票ID索引的特征映射
     * @param roundTime      本轮时间
     */
    private void evaluateExits(RoundSnapshot snapshot,
                                Map<Integer, TornStockMarketBar15mDO> barByStock,
                                Map<Integer, TornStockStrategyFeature15mDO> featureByStock,
                                LocalDateTime roundTime) {
        List<TornStockVirtualBatchDO> openBatches = snapshot.activeBatches().stream()
                .filter(batch -> StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus()))
                .toList();

        for (TornStockVirtualBatchDO batch : openBatches) {
            TornStockMarketBar15mDO currentBar = barByStock.get(batch.getStocksId());
            if (currentBar == null || !Stock15mBarBuildService.isUsable(currentBar)) {
                continue;
            }

            TornStockStrategyFeature15mDO feature = featureByStock.get(batch.getStocksId());
            BigDecimal position30 = feature != null ? feature.getPosition30() : null;
            BigDecimal low30d = feature != null ? feature.getLow30d() : null;
            BigDecimal high30d = feature != null ? feature.getHigh30d() : null;

            ExitEvaluation evaluation = batchExitService.evaluateExit(
                    batch, currentBar.getLastPrice(), position30, low30d, high30d);

            if (evaluation.shouldExit()) {
                batch.setBatchStatus(StockBatchStatusEnum.EXIT_PENDING.getCode());
                batch.setExitSignalTime(roundTime);
                batch.setExpectedExitBarTime(roundTime.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES));
                batch.setExitReason(evaluation.closeType() != null ? evaluation.closeType().getCode() : null);
                log.info("开放批次触发退出: batchNo={}, stocksId={}, closeType={}, reason={}",
                        batch.getBatchNo(), batch.getStocksId(),
                        evaluation.closeType(), evaluation.reason());
            }
        }
    }

    // ==================== 步骤6: 评估买入信号 ====================

    /**
     * 评估本轮买入信号(false-&gt;true边沿)与资格,收集正式候选。
     * <p>
     * 对每支有特征数据的股票:
     * <ol>
     *   <li>组装BuyContext</li>
     *   <li>遍历3个买入策略,调用matches()判断是否命中</li>
     *   <li>对命中的策略计算质量分,选取主策略(质量分最高)</li>
     *   <li>false-&gt;true边沿检查: 比较signalState.conditionActive与本轮matches结果</li>
     *   <li>调用StockEligibilityService.checkEligibility</li>
     *   <li>ALLOWED的候选加入正式候选列表</li>
     * </ol>
     *
     * @param snapshot            轮次快照
     * @param barByStock          按股票ID索引的bar映射
     * @param featureByStock      按股票ID索引的特征映射
     * @param monthlyStateByStock 按股票ID索引的月度状态映射
     * @param signalStateByStock  按股票ID索引的信号状态映射
     * @param roundTime           本轮时间
     * @param formalCandidates    输出: 通过资格的正式候选列表
     * @param allEvaluations      输出: 全部信号评估结果(含拒绝/观察)
     */
    private void evaluateBuySignals(RoundSnapshot snapshot,
                                     Map<Integer, TornStockMarketBar15mDO> barByStock,
                                     Map<Integer, TornStockStrategyFeature15mDO> featureByStock,
                                     Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock,
                                     Map<Integer, TornStockSignalStateDO> signalStateByStock,
                                     LocalDateTime roundTime,
                                     List<CandidateInfo> formalCandidates,
                                     List<SignalEvaluation> allEvaluations) {
        Set<Integer> activeFormalStockIds = collectActiveFormalStockIds(snapshot.activeBatches());

        for (TornStockStrategyFeature15mDO feature : snapshot.features()) {
            if (!Boolean.TRUE.equals(feature.getStrategyReady())) {
                continue;
            }

            Integer stocksId = feature.getStocksId();
            TornStockMarketBar15mDO bar = barByStock.get(stocksId);
            if (bar == null || !Stock15mBarBuildService.isUsable(bar)) {
                continue;
            }

            TornStockMonthlyStateDO monthlyState = monthlyStateByStock.get(stocksId);
            BuyContext context = buildBuyContext(feature, bar, monthlyState);
            if (context == null) {
                continue;
            }

            // 遍历策略匹配
            List<StockBuyStrategy> matchedStrategies = new ArrayList<>();
            StockBuyStrategy primaryStrategy = null;
            BigDecimal bestScore = null;

            for (StockBuyStrategy strategy : buyStrategies) {
                if (!strategy.isApplicableStyle(context.stylePrior())) {
                    continue;
                }
                if (strategy.matches(context)) {
                    matchedStrategies.add(strategy);
                    BigDecimal score = strategy.calculateQualityScore(context);
                    if (primaryStrategy == null || (bestScore != null && score.compareTo(bestScore) > 0)) {
                        primaryStrategy = strategy;
                        bestScore = score;
                    }
                }
            }

            boolean currentMatches = !matchedStrategies.isEmpty();
            TornStockSignalStateDO signalState = signalStateByStock.get(stocksId);
            boolean previousActive = signalState != null && Boolean.TRUE.equals(signalState.getConditionActive());

            // false->true边沿检查
            boolean edgeTriggered = currentMatches && !previousActive;

            SignalEvaluation evaluation = new SignalEvaluation(
                    stocksId,
                    context.stocksShortname(),
                    primaryStrategy,
                    matchedStrategies,
                    bestScore,
                    currentMatches,
                    edgeTriggered,
                    context,
                    signalState,
                    monthlyState
            );
            allEvaluations.add(evaluation);

            if (!edgeTriggered) {
                continue;
            }

            // 资格检查
            boolean hasActiveFormalBatch = activeFormalStockIds.contains(stocksId);
            EligibilityResult eligibility = eligibilityService.checkEligibility(
                    context, signalState, monthlyState, hasActiveFormalBatch);

            evaluation.eligibilityResult = eligibility;

            if (StockEligibilityResultEnum.ALLOWED == eligibility.result() && primaryStrategy != null) {
                CandidateInfo candidate = new CandidateInfo(
                        stocksId,
                        context.stocksShortname(),
                        primaryStrategy.getStrategyType(),
                        matchedStrategies.stream().map(s -> s.getStrategyType().getCode()).toList(),
                        bestScore
                );
                formalCandidates.add(candidate);
                log.info("买入信号通过资格: stocksId={}, strategy={}, score={}",
                        stocksId, primaryStrategy.getStrategyType(), bestScore);
            } else {
                log.info("买入信号未通过资格: stocksId={}, result={}, reasons={}",
                        stocksId, eligibility.result(), eligibility.reasons());
            }
        }
    }

    /**
     * 从特征与bar组装BuyContext。
     *
     * @param feature      策略特征
     * @param bar          本轮bar
     * @param monthlyState 月度状态
     * @return BuyContext;风格缺失时返回null
     */
    private BuyContext buildBuyContext(TornStockStrategyFeature15mDO feature,
                                        TornStockMarketBar15mDO bar,
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
                styleWrapper.style,
                styleWrapper.maturity,
                styleWrapper.riskLevel
        );
    }

    /**
     * 从月度状态解析风格、成熟度、风险等级。
     *
     * @param monthlyState 月度状态
     * @return 风格包装对象;风格为空时返回null
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

    // ==================== 步骤7: 接纳正式候选 ====================

    /**
     * 按排序结果接纳正式候选,检查可用槽位并预留。
     * <p>
     * 遍历排序后的候选列表,对每个候选:
     * <ol>
     *   <li>查找可用槽位(findAvailableSlot)</li>
     *   <li>计算股数(calculateQuantity)</li>
     *   <li>股数&gt;0时创建正式批次(ENTRY_PENDING),预留槽位</li>
     *   <li>股数=0或无槽位时跳过</li>
     * </ol>
     *
     * @param rankedCandidates 排序后的候选列表
     * @param snapshot          轮次快照
     * @param barByStock        按股票ID索引的bar映射
     * @param roundTime         本轮时间
     * @param newFormalBatches  输出: 新建的正式批次列表
     */
    private void acceptFormalCandidates(List<CandidateInfo> rankedCandidates,
                                         RoundSnapshot snapshot,
                                         Map<Integer, TornStockMarketBar15mDO> barByStock,
                                         LocalDateTime roundTime,
                                         List<TornStockVirtualBatchDO> newFormalBatches) {
        int candidateRank = 0;
        for (CandidateInfo candidate : rankedCandidates) {
            candidateRank++;
            Optional<TornStockPortfolioSlotDO> slotOpt = portfolioService.findAvailableSlot();
            if (slotOpt.isEmpty()) {
                log.info("无可用槽位,停止接纳候选: stocksId={}, rank={}", candidate.stocksId(), candidateRank);
                break;
            }

            TornStockPortfolioSlotDO slot = slotOpt.get();
            TornStockMarketBar15mDO bar = barByStock.get(candidate.stocksId());
            if (bar == null || bar.getLastPrice() == null || bar.getLastPrice().signum() <= 0) {
                log.warn("候选[{}]本轮bar无效,跳过", candidate.stocksId());
                continue;
            }

            BigDecimal signalReferencePrice = bar.getLastPrice();
            Long quantity = StockPortfolioService.calculateQuantity(slot.getAvailableCash(), signalReferencePrice);
            if (quantity <= 0) {
                log.info("候选[{}]可用资金不足买入1股,跳过: availableCash={}, price={}",
                        candidate.stocksId(), slot.getAvailableCash(), signalReferencePrice);
                continue;
            }

            // 创建正式批次(暂不入库,等batchSaveChanges统一保存)
            TornStockVirtualBatchDO batch = createFormalBatch(candidate, slot, bar,
                    signalReferencePrice, quantity, roundTime, candidateRank);
            newFormalBatches.add(batch);

            // 预留槽位(预留资金=信号参考价×股数)
            BigDecimal reservedAmount = signalReferencePrice.multiply(BigDecimal.valueOf(quantity));
            portfolioService.reserveSlot(slot, reservedAmount, null);

            log.info("正式候选接纳: stocksId={}, rank={}, slotNo={}, signalPrice={}, quantity={}, reserved={}",
                    candidate.stocksId(), candidateRank, slot.getSlotNo(),
                    signalReferencePrice, quantity, reservedAmount);
        }
    }

    /**
     * 创建正式批次DO(ENTRY_PENDING状态)。
     *
     * @param candidate            候选信息
     * @param slot                 分配的槽位
     * @param bar                  本轮bar
     * @param signalReferencePrice 信号参考价
     * @param quantity             计划买入股数
     * @param roundTime            本轮时间
     * @param candidateRank        候选排名
     * @return 未保存的正式批次DO
     */
    private TornStockVirtualBatchDO createFormalBatch(CandidateInfo candidate,
                                                       TornStockPortfolioSlotDO slot,
                                                       TornStockMarketBar15mDO bar,
                                                       BigDecimal signalReferencePrice,
                                                       Long quantity,
                                                       LocalDateTime roundTime,
                                                       int candidateRank) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setBatchNo("F" + roundTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")) + candidate.stocksId());
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
        batch.setSignalReferencePrice(signalReferencePrice);
        batch.setExpectedEntryBarTime(roundTime.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES));
        batch.setEntryStaleAt(StockPortfolioService.calculateEntryStaleAt(roundTime));
        batch.setQuantity(quantity);
        batch.setBuyRuleVersion(BUY_RULE_VERSION);
        batch.setSellRuleVersion(SELL_RULE_VERSION);
        batch.setAllocationRuleVersion(ALLOCATION_RULE_VERSION);
        batch.setMessageRuleVersion(MESSAGE_RULE_VERSION);
        batch.setResetObserved(false);
        return batch;
    }

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
     * @param roundTime       本轮时间
     */
    private void writeShadowRecords(List<SignalEvaluation> allEvaluations, LocalDateTime roundTime) {
        for (SignalEvaluation evaluation : allEvaluations) {
            if (!evaluation.edgeTriggered || evaluation.primaryStrategy == null) {
                continue;
            }

            EligibilityResult eligibility = evaluation.eligibilityResult;
            String eligibilityResultCode = eligibility != null ? eligibility.result().getCode() : null;
            List<String> eligibilityReasons = eligibility != null ? eligibility.reasons() : List.of();
            String portfolioDecision = determinePortfolioDecision(evaluation, eligibility);
            String rejectReason = determineRejectReason(evaluation, eligibility);

            StockSignalEventContext context = new StockSignalEventContext(
                    evaluation.stocksId,
                    evaluation.stocksShortname,
                    evaluation.primaryStrategy.getStrategyType().getCode(),
                    BUY_RULE_VERSION,
                    evaluation.qualityScore,
                    buildFeatureSnapshot(evaluation.context),
                    buildStyleSnapshot(evaluation.monthlyState),
                    eligibilityResultCode,
                    eligibilityReasons,
                    evaluation.candidateRank,
                    portfolioDecision,
                    rejectReason,
                    roundTime
            );

            TornStockSignalEventDO event = shadowService.recordSignalEvent(context);

            // 根据决策创建影子或拒绝观察批次
            if (DECISION_SHADOW.equals(portfolioDecision)) {
                shadowService.createUnlimitedShadowBatch(event);
            } else if (DECISION_REJECTED.equals(portfolioDecision)) {
                shadowService.createRejectedObservationBatch(event, rejectReason);
            }
        }
    }

    /**
     * 判定组合决策编码。
     * <p>
     * ALLOWED且已入选正式 -&gt; FORMAL;ALLOWED但未入选(无槽位/资金不足) -&gt; SHADOW;
     * REJECTED/OBSERVED -&gt; REJECTED。
     *
     * @param evaluation 信号评估
     * @param eligibility 资格结果
     * @return 组合决策编码
     */
    private String determinePortfolioDecision(SignalEvaluation evaluation, EligibilityResult eligibility) {
        if (eligibility == null || StockEligibilityResultEnum.ALLOWED != eligibility.result()) {
            return DECISION_REJECTED;
        }
        // ALLOWED但未被正式接纳(无槽位或资金不足)
        if (!evaluation.acceptedFormal) {
            return DECISION_SHADOW;
        }
        return DECISION_FORMAL;
    }

    /**
     * 判定拒绝原因编码。
     *
     * @param evaluation 信号评估
     * @param eligibility 资格结果
     * @return 拒绝原因编码;非拒绝时返回null
     */
    private String determineRejectReason(SignalEvaluation evaluation, EligibilityResult eligibility) {
        if (eligibility == null || StockEligibilityResultEnum.ALLOWED == eligibility.result()) {
            return null;
        }
        if (eligibility.reasons() == null || eligibility.reasons().isEmpty()) {
            return "UNKNOWN";
        }
        return eligibility.reasons().getFirst();
    }

    /**
     * 构建特征快照JSON。
     *
     * @param context 买入上下文
     * @return 特征快照JSON文本
     */
    private String buildFeatureSnapshot(BuyContext context) {
        if (context == null) {
            return "{}";
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
     * @return 风格快照JSON文本
     */
    private String buildStyleSnapshot(TornStockMonthlyStateDO monthlyState) {
        if (monthlyState == null) {
            return "{}";
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
     * 对每个已成交的卖出批次创建SELL类型PENDING通知;
     * 对每个新建的正式ENTRY_PENDING批次不创建通知(待实际成交后创建)。
     *
     * @param entryFilledBatches 已成交买入批次
     * @param exitFilledBatches  已成交卖出批次
     * @param newFormalBatches   新建正式批次
     * @param roundTime          本轮时间
     */
    private void writeNoticeAudits(List<TornStockVirtualBatchDO> entryFilledBatches,
                                    List<TornStockVirtualBatchDO> exitFilledBatches,
                                    List<TornStockVirtualBatchDO> newFormalBatches,
                                    LocalDateTime roundTime) {
        List<TornStockNoticeAuditDO> notices = new ArrayList<>();

        for (TornStockVirtualBatchDO batch : entryFilledBatches) {
            notices.add(buildNoticeAudit(batch, StockNoticeTypeEnum.BUY, roundTime));
        }
        for (TornStockVirtualBatchDO batch : exitFilledBatches) {
            notices.add(buildNoticeAudit(batch, StockNoticeTypeEnum.SELL, roundTime));
        }

        if (!notices.isEmpty()) {
            noticeAuditDAO.saveBatch(notices);
            log.info("通知审计写入完成: buyNotices={}, sellNotices={}",
                    entryFilledBatches.size(), exitFilledBatches.size());
        }
    }

    /**
     * 构建通知审计DO(PENDING状态)。
     *
     * @param batch     关联批次
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
        notice.setMessageRuleVersion(MESSAGE_RULE_VERSION);
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
                ? String.valueOf(batch.getId() % 1000000) : "0";
        return NOTICE_NO_PREFIX + timestamp + batchSuffix + noticeType.getCode().charAt(0);
    }

    /**
     * 生成通知载荷哈希(简化版:用batchId+noticeType+roundTime拼接)。
     *
     * @param batch      关联批次
     * @param noticeType 通知类型
     * @return 载荷哈希
     */
    private String generatePayloadHash(TornStockVirtualBatchDO batch, StockNoticeTypeEnum noticeType) {
        return Integer.toHexString((batch.getId() + "_" + noticeType.getCode()).hashCode());
    }

    /**
     * 构建通知载荷快照JSON。
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

    // ==================== 步骤10: 更新信号边沿状态 ====================

    /**
     * 更新信号边沿状态:记录本轮matches结果为conditionActive,边沿触发时更新lastSignalTime。
     * <p>
     * 对每个有评估结果的股票,更新signalState.conditionActive为本轮matches结果,
     * 边沿触发时更新lastSignalTime和lastEvaluatedRoundTime。
     *
     * @param allEvaluations   全部信号评估结果
     * @param signalStateByStock 按股票ID索引的信号状态映射
     * @param roundTime        本轮时间
     */
    private void updateSignalStates(List<SignalEvaluation> allEvaluations,
                                     Map<Integer, TornStockSignalStateDO> signalStateByStock,
                                     LocalDateTime roundTime) {
        List<TornStockSignalStateDO> toSave = new ArrayList<>();

        for (SignalEvaluation evaluation : allEvaluations) {
            TornStockSignalStateDO state = signalStateByStock.get(evaluation.stocksId);
            boolean isNew = false;

            if (state == null) {
                state = new TornStockSignalStateDO();
                state.setStocksId(evaluation.stocksId);
                state.setStrategyType(evaluation.primaryStrategy != null
                        ? evaluation.primaryStrategy.getStrategyType().getCode()
                        : evaluation.matchedStrategies.isEmpty() ? null
                        : evaluation.matchedStrategies.getFirst().getStrategyType().getCode());
                state.setBuyRuleVersion(BUY_RULE_VERSION);
                state.setResetObserved(false);
                isNew = true;
            }

            state.setConditionActive(evaluation.currentMatches);
            state.setLastEvaluatedRoundTime(roundTime);

            if (evaluation.edgeTriggered) {
                state.setLastSignalTime(roundTime);
            }

            // 如果条件从true变为false,标记复位已观察
            if (!evaluation.currentMatches && Boolean.TRUE.equals(state.getConditionActive())) {
                state.setResetObserved(true);
            }

            toSave.add(state);
        }

        if (!toSave.isEmpty()) {
            signalStateDAO.saveOrUpdateBatch(toSave);
            log.debug("信号边沿状态更新: count={}", toSave.size());
        }
    }

    // ==================== 步骤11: 批量保存与完成轮次 ====================

    /**
     * 批量保存全部变更的DO(批次、槽位、标记)。
     *
     * @param snapshot 轮次快照(含变更后的批次与槽位)
     * @param marks    生成的BatchMark列表
     */
    private void batchSaveChanges(RoundSnapshot snapshot, List<TornStockBatchMarkDO> marks) {
        // 保存变更的批次(成交/取消/关闭/新建)
        List<TornStockVirtualBatchDO> allBatches = new ArrayList<>(snapshot.activeBatches());
        if (!allBatches.isEmpty()) {
            virtualBatchDAO.saveOrUpdateBatch(allBatches);
        }

        // 保存变更的槽位
        List<TornStockPortfolioSlotDO> allSlots = snapshot.slots();
        if (!allSlots.isEmpty()) {
            portfolioSlotDAO.updateBatchById(allSlots);
        }

        // 保存BatchMark
        if (!marks.isEmpty()) {
            batchMarkDAO.saveBatch(marks);
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
        round.setUsableStockCount((int) snapshot.features().stream()
                .filter(f -> Boolean.TRUE.equals(f.getStrategyReady()))
                .count());
        marketRoundDAO.updateById(round);
    }

    // ==================== 辅助方法 ====================

    /**
     * 按股票ID索引bar列表。
     *
     * @param bars bar列表
     * @return 按股票ID索引的映射
     */
    private Map<Integer, TornStockMarketBar15mDO> indexBarsByStock(List<TornStockMarketBar15mDO> bars) {
        Map<Integer, TornStockMarketBar15mDO> map = new HashMap<>();
        if (bars == null) {
            return map;
        }
        for (TornStockMarketBar15mDO bar : bars) {
            map.put(bar.getStocksId(), bar);
        }
        return map;
    }

    /**
     * 按股票ID索引特征列表。
     *
     * @param features 特征列表
     * @return 按股票ID索引的映射
     */
    private Map<Integer, TornStockStrategyFeature15mDO> indexFeaturesByStock(
            List<TornStockStrategyFeature15mDO> features) {
        Map<Integer, TornStockStrategyFeature15mDO> map = new HashMap<>();
        if (features == null) {
            return map;
        }
        for (TornStockStrategyFeature15mDO feature : features) {
            map.put(feature.getStocksId(), feature);
        }
        return map;
    }

    /**
     * 按股票ID索引月度状态列表。
     *
     * @param monthlyStates 月度状态列表
     * @return 按股票ID索引的映射
     */
    private Map<Integer, TornStockMonthlyStateDO> indexMonthlyStatesByStock(
            List<TornStockMonthlyStateDO> monthlyStates) {
        Map<Integer, TornStockMonthlyStateDO> map = new HashMap<>();
        if (monthlyStates == null) {
            return map;
        }
        for (TornStockMonthlyStateDO state : monthlyStates) {
            map.put(state.getStocksId(), state);
        }
        return map;
    }

    /**
     * 按股票ID索引信号状态列表。
     *
     * @param signalStates 信号状态列表
     * @return 按股票ID索引的映射
     */
    private Map<Integer, TornStockSignalStateDO> indexSignalStatesByStock(
            List<TornStockSignalStateDO> signalStates) {
        Map<Integer, TornStockSignalStateDO> map = new HashMap<>();
        if (signalStates == null) {
            return map;
        }
        for (TornStockSignalStateDO state : signalStates) {
            map.put(state.getStocksId(), state);
        }
        return map;
    }

    /**
     * 按槽位ID索引槽位列表。
     *
     * @param slots 槽位列表
     * @return 按槽位ID索引的映射
     */
    private Map<Long, TornStockPortfolioSlotDO> indexSlotsById(List<TornStockPortfolioSlotDO> slots) {
        Map<Long, TornStockPortfolioSlotDO> map = new HashMap<>();
        if (slots == null) {
            return map;
        }
        for (TornStockPortfolioSlotDO slot : slots) {
            if (slot.getId() != null) {
                map.put(slot.getId(), slot);
            }
        }
        return map;
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

    /**
     * 计算MFE(最大有利偏移)。
     * <p>
     * mfe = (peakPrice - entryReferencePrice) / entryReferencePrice
     *
     * @param entryReferencePrice 入场参考价
     * @param peakPrice           峰值价格
     * @return MFE;入场价为非正数时返回0
     */
    private BigDecimal calculateMfe(BigDecimal entryReferencePrice, BigDecimal peakPrice) {
        if (entryReferencePrice == null || entryReferencePrice.signum() <= 0 || peakPrice == null) {
            return BigDecimal.ZERO;
        }
        return peakPrice
                .subtract(entryReferencePrice)
                .divide(entryReferencePrice, MATH_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算MAE(最大不利偏移)。
     * <p>
     * mae = (troughPrice - entryReferencePrice) / entryReferencePrice
     *
     * @param entryReferencePrice 入场参考价
     * @param troughPrice         谷值价格
     * @return MAE;入场价为非正数时返回0
     */
    private BigDecimal calculateMae(BigDecimal entryReferencePrice, BigDecimal troughPrice) {
        if (entryReferencePrice == null || entryReferencePrice.signum() <= 0 || troughPrice == null) {
            return BigDecimal.ZERO;
        }
        return troughPrice
                .subtract(entryReferencePrice)
                .divide(entryReferencePrice, MATH_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算峰值回撤。
     * <p>
     * peakDrawdown = (troughPrice - peakPrice) / peakPrice(结果为负数或0)
     *
     * @param peakPrice   峰值价格
     * @param troughPrice 谷值价格
     * @return 峰值回撤;峰值为非正数时返回0
     */
    private BigDecimal calculatePeakDrawdown(BigDecimal peakPrice, BigDecimal troughPrice) {
        if (peakPrice == null || peakPrice.signum() <= 0 || troughPrice == null) {
            return BigDecimal.ZERO;
        }
        return troughPrice
                .subtract(peakPrice)
                .divide(peakPrice, MATH_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 安全解析关闭类型枚举,解析失败时返回CLOSED_TARGET。
     *
     * @param code 关闭类型编码
     * @return 关闭类型枚举
     */
    private StockCloseTypeEnum safeParseCloseType(String code) {
        try {
            return StockCloseTypeEnum.fromCode(code);
        } catch (IllegalArgumentException e) {
            log.warn("关闭类型编码解析失败,使用默认值CLOSED_TARGET: code={}", code);
            return StockCloseTypeEnum.CLOSED_TARGET;
        }
    }

    /**
     * 将关闭类型映射到批次状态。
     *
     * @param closeType 关闭类型
     * @return 对应的批次状态枚举
     */
    private StockBatchStatusEnum mapCloseTypeToBatchStatus(StockCloseTypeEnum closeType) {
        return switch (closeType) {
            case CLOSED_TARGET -> StockBatchStatusEnum.CLOSED_TARGET;
            case CLOSED_RANGE -> StockBatchStatusEnum.CLOSED_RANGE;
            case CLOSED_RISK -> StockBatchStatusEnum.CLOSED_RISK;
            case CLOSED_TIME -> StockBatchStatusEnum.CLOSED_TIME;
            case CLOSED_DYNAMIC -> StockBatchStatusEnum.CLOSED_DYNAMIC;
            case CLOSED_ROTATION -> StockBatchStatusEnum.CLOSED_ROTATION;
            case ADMIN_CLOSED -> StockBatchStatusEnum.ADMIN_CLOSED;
        };
    }

    // ==================== 内部值对象 ====================

    /**
     * 信号评估结果 - 封装单支股票本轮买入信号评估的全部中间结果。
     * <p>
     * 由{@link #evaluateBuySignals}生成,供后续影子记录、通知审计与信号状态更新消费。
     *
     * @param stocksId          股票ID
     * @param stocksShortname   股票简称
     * @param primaryStrategy   主策略(质量分最高的命中策略)
     * @param matchedStrategies 全部命中策略列表
     * @param qualityScore      主策略质量分
     * @param currentMatches    本轮是否命中任何策略
     * @param edgeTriggered     是否为false-&gt;true边沿触发
     * @param context           买入上下文
     * @param signalState       信号状态记录
     * @param monthlyState      月度状态记录
     */
    private static class SignalEvaluation {
        final Integer stocksId;
        final String stocksShortname;
        final StockBuyStrategy primaryStrategy;
        final List<StockBuyStrategy> matchedStrategies;
        final BigDecimal qualityScore;
        final boolean currentMatches;
        final boolean edgeTriggered;
        final BuyContext context;
        final TornStockSignalStateDO signalState;
        final TornStockMonthlyStateDO monthlyState;
        EligibilityResult eligibilityResult;
        Integer candidateRank;
        boolean acceptedFormal;

        SignalEvaluation(Integer stocksId, String stocksShortname,
                         StockBuyStrategy primaryStrategy,
                         List<StockBuyStrategy> matchedStrategies,
                         BigDecimal qualityScore,
                         boolean currentMatches,
                         boolean edgeTriggered,
                         BuyContext context,
                         TornStockSignalStateDO signalState,
                         TornStockMonthlyStateDO monthlyState) {
            this.stocksId = stocksId;
            this.stocksShortname = stocksShortname;
            this.primaryStrategy = primaryStrategy;
            this.matchedStrategies = matchedStrategies;
            this.qualityScore = qualityScore;
            this.currentMatches = currentMatches;
            this.edgeTriggered = edgeTriggered;
            this.context = context;
            this.signalState = signalState;
            this.monthlyState = monthlyState;
        }
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
