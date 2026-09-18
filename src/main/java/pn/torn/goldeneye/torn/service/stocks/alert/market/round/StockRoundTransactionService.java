package pn.torn.goldeneye.torn.service.stocks.alert.market.round;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRuleModeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockBatchMarkDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision.StockAlphaDecisionService;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision.StockAlphaTargetPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution.StockAlphaEntryService;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution.StockAlphaExecutionBarPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution.StockAlphaRebalanceService;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaPhaseTrack;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaTrackRegistry;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundFactory;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockRuleVersion;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeAuditWriter;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockBatchPathService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockEntrySettlementService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockEntrySettlementService.EntrySettlementResult;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 股票轮次事务服务 - 短事务内编排存量结算、路径管理与α轨道决策。
 * <p>
 * 本类为纯编排入口,在单个数据库事务内按固定顺序调用各步骤处理器,保证原子性;
 * NapCat消息投递不进入本事务。
 * <p>
 * α侧按 {@link StockAlphaTrackRegistry#enabledTracks()} 逐轨道编排:每条轨道取自己的当前开放批次,
 * 用自己的相位轨道生成/复用决策,再做初始入场或原子换仓。影子轨道只由影子开关控制,
 * 不消费正式新入场开关;正式轨道的槽位锁定、批次锁定、路径评估与结算语义保持原样。
 *
 * <h3>执行顺序</h3>
 * <ol>
 *   <li>创建/锁定轮次记录,状态置为PROCESSING</li>
 *   <li>按固定顺序锁定正式组合与各α轨道组合的槽位,以及正式与各α轨道的活跃批次</li>
 *   <li>各α轨道:无活跃批次且许可成立时生成/复用初始决策并创建待入场批次</li>
 *   <li>处理上一轮待买入批次(成交/取消/过期)</li>
 *   <li>处理上一轮待卖出批次(成交并释放槽位)</li>
 *   <li>更新开放批次峰谷、MFE/MAE、回撤,评估退出条件并写入逐轮mark</li>
 *   <li>各α轨道:消费同执行桶的目标变化决策并原子换仓</li>
 *   <li>为已成交买入/卖出写入PENDING通知审计</li>
 *   <li>批量保存批次、槽位与mark,更新轮次为COMPLETED</li>
 * </ol>
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRoundTransactionService {

    /**
     * 卖出规则版本
     */
    public static final String SELL_RULE_VERSION = StockRuleVersion.SELL;
    /**
     * 消息通知规则版本
     */
    public static final String MESSAGE_RULE_VERSION = StockRuleVersion.MESSAGE;

    private final TornStockMarketRoundDAO marketRoundDao;
    private final TornStockVirtualBatchDAO virtualBatchDao;
    private final TornStockPortfolioSlotDAO portfolioSlotDao;
    private final TornStockBatchMarkDAO batchMarkDao;

    private final StockEntrySettlementService entrySettlementService;
    private final StockAlphaEntryService alphaEntryService;
    private final StockAlphaDecisionService alphaDecisionService;
    private final StockAlphaRebalanceService alphaRebalanceService;
    private final StockBatchPathService batchPathService;
    private final StockNoticeAuditWriter noticeAuditWriter;
    private final StockAlphaTrackRegistry trackRegistry;
    private final SysSettingManager sysSettingManager;
    private final StockMarketRoundFactory roundFactory;
    private final StockMarketClock marketClock;

    /**
     * 执行一轮的全部写操作。
     * <p>
     * 传入的{@link RoundSnapshot}在事务外已批量加载,事务内不再产生N+1查询。
     * <p>
     * {@code allowNewEntry=false} 时仍完整执行ENTRY/EXIT结算、存量路径管理、灾难关闭、冷却与通知审计,
     * 仅禁止正式α轨道的初始入场,确保紧急回滚不遗弃已存在的正式持仓;α影子轨道的初始入场由影子开关
     * 单独控制,不受本开关影响。
     * <p>
     * 正式α新入场只允许{@code FORMAL}:门禁已经收敛为FORMAL-only,本方法仍以本地解析的规则模式
     * 再做一次同源防线,保证{@code SHADOW}/{@code PROVISIONAL}不会因任何调用方传入的
     * {@code allowNewEntry=true} 创建{@code VIP_ALPHA}正式批次。
     * 已有α批次的存量管理、ENTRY_PENDING结算、换仓与通知审计不消费本开关。
     * <p>
     * 时间语义: {@code roundTime} 是历史决策/成交bar的业务锚点;
     * {@code actualProcessingTime} 是本轮真实执行/恢复时刻,由调度层通过
     * {@link StockMarketClock#now()} 获得一次后显式传入,同一轮内不再反复取当前时间。
     * 仅ENTRY过期(entryStaleAt)判定使用实际处理时刻,避免启动补偿晚恢复时把历史轮次
     * 当作未过期而补发BUY。
     *
     * @param roundTime            本轮bar开始时间(历史决策锚点)
     * @param snapshot             事务外已加载的批量数据快照
     * @param allowNewEntry        是否允许正式α新入场(门禁已确保仅{@code FORMAL}成立)
     * @param actualProcessingTime 本次实际处理时刻(仅用于ENTRY过期判定)
     */
    @Transactional(rollbackFor = Exception.class)
    public void executeRound(LocalDateTime roundTime, RoundSnapshot snapshot, boolean allowNewEntry,
                             LocalDateTime actualProcessingTime) {
        Objects.requireNonNull(roundTime, "轮次时间不能为空");
        Objects.requireNonNull(snapshot, "轮次快照不能为空");
        Objects.requireNonNull(actualProcessingTime, "实际处理时刻不能为空");
        List<StockAlphaPhaseTrack> tracks = trackRegistry.enabledTracks();
        log.info("轮次事务开始: roundTime={}, allowNewEntry={}, actualProcessingTime={}, alphaTracks={}",
                roundTime, allowNewEntry, actualProcessingTime, tracks.size());

        // 步骤1: 创建/锁定轮次记录
        TornStockMarketRoundDO round = lockOrCreateRound(roundTime, snapshot);

        // 行锁落地: 在事务内重新锁定正式组合与各α轨道组合的全部槽位(FOR UPDATE),
        // 替换Loader在事务外读取的快照槽位,保证槽位分配与状态变更的并发安全。
        // 固定组合顺序: 先正式组合,再按注册表顺序逐个α轨道,避免死锁。
        List<TornStockPortfolioSlotDO> lockedSlots =
                portfolioSlotDao.selectAllByPortfolioCodeForUpdate(StockPortfolioService.PORTFOLIO_CODE);
        for (StockAlphaPhaseTrack track : tracks) {
            lockedSlots = mergeSlots(lockedSlots,
                    portfolioSlotDao.selectAllByPortfolioCodeForUpdate(track.portfolioCode()));
        }

        // 活跃批次必须在同一事务内重新读取并加行锁,不能继续使用事务外快照。
        List<TornStockVirtualBatchDO> allActiveBatches =
                mergeActiveBatches(virtualBatchDao.selectActiveFormalBatchesForUpdate(), null);
        for (StockAlphaPhaseTrack track : tracks) {
            allActiveBatches = mergeActiveBatches(allActiveBatches,
                    virtualBatchDao.selectActiveAlphaBatchesForUpdate(track.portfolioCode()));
        }
        log.debug("行锁获取完成: slots={}, activeBatches={}, alphaTracks={}",
                lockedSlots.size(), allActiveBatches.size(), tracks.size());

        Map<Integer, TornStockMarketBar15mDO> barByStock = indexBarsByStockId(snapshot.bars());
        Map<Integer, TornStockStrategyFeature15mDO> featureByStock = indexFeaturesByStockId(snapshot.features());
        RoundSnapshot mergedSnapshot = new RoundSnapshot(snapshot.bars(), snapshot.features(),
                snapshot.monthlyStates(), allActiveBatches, snapshot.signalStates(),
                lockedSlots, roundTime);

        // 正式新入场许可:门禁已收敛为FORMAL-only,这里以同一规则再校验一次,
        // 禁止SHADOW/PROVISIONAL通过任何调用方创建VIP_ALPHA正式批次。
        StockRuleModeEnum ruleMode = StockRuleModeEnum.resolve(
                sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE));
        boolean formalNewEntryAllowed = allowNewEntry && ruleMode == StockRuleModeEnum.FORMAL;

        // 各α轨道初始入场: 正式轨道消费FORMAL-only许可,影子轨道由影子开关(轨道注册表)决定
        boolean entryAttempted = false;
        for (StockAlphaPhaseTrack track : tracks) {
            if (hasAlphaBatch(mergedSnapshot, track) || !isInitialEntryAllowed(track, formalNewEntryAllowed)) {
                continue;
            }
            createInitialAlphaEntry(track, roundTime, actualProcessingTime, mergedSnapshot, barByStock);
            entryAttempted = true;
        }
        if (entryAttempted) {
            mergedSnapshot = refreshAlphaBatches(mergedSnapshot, tracks);
        }

        // 步骤2: 处理待买入批次(ENTRY_PENDING) - 含正式与各α轨道
        EntrySettlementResult entryResult = entrySettlementService.processEntryPending(
                mergedSnapshot, barByStock, roundTime, actualProcessingTime);

        // 步骤3: 处理待卖出批次
        List<TornStockVirtualBatchDO> exitFilledBatches = entrySettlementService.processExitPending(
                mergedSnapshot, barByStock, roundTime);

        // 步骤4-5: 更新开放批次路径、评估退出并生成包含实际决定的mark
        List<TornStockBatchMarkDO> marks = batchPathService.updatePathsAndEvaluateExits(
                mergedSnapshot, barByStock, featureByStock, roundTime);

        // 步骤6: 各α轨道消费同执行桶的目标变化决策并原子换仓
        for (StockAlphaPhaseTrack track : tracks) {
            if (!hasAlphaBatch(mergedSnapshot, track)) {
                continue;
            }
            processAlphaRebalance(track, roundTime, actualProcessingTime, mergedSnapshot, barByStock);
        }

        // 步骤9: 为已成交的买入/卖出写入PENDING通知审计(不受新入场开关影响)
        noticeAuditWriter.writeNoticeAudits(entryResult.filledBatches(), exitFilledBatches, roundTime);

        // 批量保存变更
        batchSaveChanges(mergedSnapshot, marks);

        // 更新轮次为COMPLETED
        completeRound(round, mergedSnapshot);

        log.info("轮次事务完成: roundTime={}, entryFilled={}, entryCancelled={}, exitFilled={}, marks={}",
                roundTime, entryResult.filledBatches().size(), entryResult.cancelledBatches().size(),
                exitFilledBatches.size(), marks.size());
    }

    /**
     * 判断指定轨道本轮是否允许初始入场。
     * <p>
     * 正式轨道消费{@code VIP_STOCK_NEW_ENTRY_ENABLED}与FORMAL规则的合取许可;
     * 影子轨道只由影子开关控制(该开关成立时轨道才会出现在启用列表中),
     * 因此不消费正式新入场开关。
     *
     * @param track                 目标相位轨道
     * @param formalNewEntryAllowed 正式新入场许可
     * @return 允许初始入场时返回true
     */
    private boolean isInitialEntryAllowed(StockAlphaPhaseTrack track, boolean formalNewEntryAllowed) {
        return !StockAlphaTrackRegistry.productionTrack().equals(track) || formalNewEntryAllowed;
    }

    /**
     * 判断快照中是否存在指定轨道的活跃批次。
     *
     * @param snapshot 当前轮次快照
     * @param track    目标相位轨道
     * @return 存在该轨道活跃批次时返回true
     */
    private boolean hasAlphaBatch(RoundSnapshot snapshot, StockAlphaPhaseTrack track) {
        return snapshot.activeBatches().stream()
                .anyMatch(batch -> track.portfolioCode().equals(batch.getPortfolioCode()));
    }

    /**
     * 在轮次事务内消费指定轨道已持久化的目标变化决策。
     * <p>
     * 决策以本轮{@code roundTime}为决策时点生成或复用:执行桶由决策服务按
     * "决策桶 + 15分钟"计算并持久化,本方法只在持久化执行桶与本轮完全一致时换仓,
     * 不跨桶追补,也不抛异常把轮次钉死在可重试失败状态。
     * <p>
     * 本轮bar即决策时点bar,必须以带可用性字段的决策bar事实传给决策服务:
     * 不可用决策bar不得固化为信号参考价,也不得形成可执行的换仓决策。
     *
     * @param track      目标相位轨道
     * @param roundTime  轮次时间(决策时点;执行桶为下一根严格连续bar)
     * @param now        当前校验时间
     * @param snapshot   当前轮次快照
     * @param barByStock 本轮按股票ID索引的行情bar
     */
    private void processAlphaRebalance(StockAlphaPhaseTrack track, LocalDateTime roundTime, LocalDateTime now,
                                       RoundSnapshot snapshot,
                                       Map<Integer, TornStockMarketBar15mDO> barByStock) {
        TornStockVirtualBatchDO current = findOpenAlphaBatch(snapshot, track);
        if (current == null) {
            return;
        }
        StockAlphaDecisionService.DecisionResult decision = alphaDecisionService.decide(track,
                roundTime.toLocalDate().minusDays(1), current.getStocksId(), current.getId(), roundTime,
                decisionBarFacts(barByStock, roundTime));
        if (!decision.ready() || decision.event() != StockAlphaTargetPolicy.TargetEvent.ALPHA_TARGET_CHANGED) {
            return;
        }
        if (!roundTime.equals(decision.executionBarStartTime())) {
            log.warn("α换仓决策执行桶与当前轮次不一致,本次不换仓且不跨桶追补: trackCode={}, decisionDate={}, phase={}, "
                            + "decisionExecutionBar={}, roundTime={}",
                    track.trackCode(), decision.decisionDate(), decision.phase(),
                    decision.executionBarStartTime(), roundTime);
            return;
        }
        alphaRebalanceService.rebalance(track, decision.decisionDate(), decision.phase(), now, snapshot);
    }

    /**
     * 刷新α批次快照,确保本轮新建批次进入入场结算链。
     *
     * @param snapshot 当前轮次快照
     * @param tracks   本轮启用的α轨道
     * @return 包含事务内最新α批次的快照
     */
    private RoundSnapshot refreshAlphaBatches(RoundSnapshot snapshot, List<StockAlphaPhaseTrack> tracks) {
        List<TornStockVirtualBatchDO> activeBatches = snapshot.activeBatches();
        for (StockAlphaPhaseTrack track : tracks) {
            activeBatches = mergeActiveBatches(activeBatches,
                    virtualBatchDao.selectActiveAlphaBatchesForUpdate(track.portfolioCode()));
        }
        return new RoundSnapshot(snapshot.bars(), snapshot.features(), snapshot.monthlyStates(),
                activeBatches, snapshot.signalStates(), snapshot.slots(), snapshot.roundTime());
    }

    /**
     * 生成并消费指定轨道当前轮次对应的α初始决策。
     * <p>
     * 本轮{@code roundTime}是决策时点:首次决策在本次生成,执行桶为下一根严格连续bar,
     * 因此本轮只落决策不入场;后续轮次复用该决策且持久化执行桶与本轮一致时才真正入场。
     * <p>
     * 决策bar事实取自本轮bar并携带可用性字段,由{@link StockAlphaExecutionBarPolicy}判定;
     * 本方法不复制可用性算法,也不查询更晚的bar。
     *
     * @param track                目标相位轨道
     * @param roundTime            轮次时间(决策时点)
     * @param actualProcessingTime 本次实际处理时刻
     * @param snapshot             当前轮次快照
     * @param barByStock           本轮按股票ID索引的行情bar
     */
    private void createInitialAlphaEntry(StockAlphaPhaseTrack track, LocalDateTime roundTime,
                                         LocalDateTime actualProcessingTime, RoundSnapshot snapshot,
                                         Map<Integer, TornStockMarketBar15mDO> barByStock) {
        StockAlphaDecisionService.DecisionResult decision = alphaDecisionService.decide(track,
                roundTime.toLocalDate().minusDays(1), null, null, roundTime, decisionBarFacts(barByStock, roundTime));
        if (!decision.ready()
                || decision.event() != StockAlphaTargetPolicy.TargetEvent.ALPHA_INITIAL_ENTRY) {
            return;
        }
        if (!roundTime.equals(decision.executionBarStartTime())) {
            log.warn("α初始入场决策执行桶与当前轮次不一致,本次不入场且不跨桶追补: trackCode={}, decisionDate={}, phase={}, "
                            + "decisionExecutionBar={}, roundTime={}",
                    track.trackCode(), decision.decisionDate(), decision.phase(),
                    decision.executionBarStartTime(), roundTime);
            return;
        }
        alphaEntryService.createInitialEntry(track, roundTime, snapshot, decision.decisionDate(),
                decision.phase(), actualProcessingTime);
    }

    /**
     * 提取决策时点(本轮已结束bar)各股票的决策bar事实,作为信号参考价的唯一候选来源。
     * <p>
     * 事实携带可用性等完整质量字段,由{@link StockAlphaExecutionBarPolicy}判定是否为合法决策bar;
     * 本方法只从本轮快照传递事实,不在此处复制可用性算法,也不查询"下一根可用bar"。
     *
     * @param barByStock 本轮按股票ID索引的行情bar
     * @param roundTime  轮次时间(决策时点)
     * @return 股票ID到决策bar事实的映射;本轮无对应bar时为空映射
     */
    private Map<Integer, StockAlphaExecutionBarPolicy.DecisionBar> decisionBarFacts(
            Map<Integer, TornStockMarketBar15mDO> barByStock, LocalDateTime roundTime) {
        Map<Integer, StockAlphaExecutionBarPolicy.DecisionBar> facts = new HashMap<>();
        barByStock.forEach((stocksId, bar) -> {
            if (roundTime.equals(bar.getBarStartTime())) {
                facts.put(stocksId, new StockAlphaExecutionBarPolicy.DecisionBar(
                        bar.getBarStartTime(), bar.getBarEndTime(),
                        Boolean.TRUE.equals(bar.getUsable()), bar.getLastPrice()));
            }
        });
        return facts;
    }

    /**
     * 查找指定轨道唯一开放持仓。
     *
     * @param snapshot 当前轮次快照
     * @param track    目标相位轨道
     * @return 开放持仓;不存在时返回null
     */
    private TornStockVirtualBatchDO findOpenAlphaBatch(RoundSnapshot snapshot, StockAlphaPhaseTrack track) {
        List<TornStockVirtualBatchDO> open = snapshot.activeBatches().stream()
                .filter(Objects::nonNull)
                .filter(batch -> track.portfolioCode().equals(batch.getPortfolioCode()))
                .filter(batch -> Integer.valueOf(track.slotNo()).equals(batch.getSlotNo()))
                .filter(batch -> StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus()))
                .toList();
        if (open.size() > 1) {
            throw new IllegalStateException("α轨道当前开放持仓数量异常: track=" + track.trackCode()
                    + ", open=" + open.size());
        }
        return open.isEmpty() ? null : open.getFirst();
    }

    /**
     * 合并正式组合与α轨道组合的槽位列表(保持顺序,按主键去重兜底)。
     *
     * @param mergedSlots 已合并槽位
     * @param trackSlots  新增槽位
     * @return 合并后的槽位列表
     */
    private List<TornStockPortfolioSlotDO> mergeSlots(List<TornStockPortfolioSlotDO> mergedSlots,
                                                      List<TornStockPortfolioSlotDO> trackSlots) {
        Map<Long, TornStockPortfolioSlotDO> slotsById = new LinkedHashMap<>();
        addSlotById(slotsById, mergedSlots);
        addSlotById(slotsById, trackSlots);
        return new ArrayList<>(slotsById.values());
    }

    /**
     * 将槽位加入主键索引;无主键对象不参与合并。
     *
     * @param slotsById 槽位索引
     * @param slots     待加入槽位
     */
    private void addSlotById(Map<Long, TornStockPortfolioSlotDO> slotsById,
                             List<TornStockPortfolioSlotDO> slots) {
        if (slots == null) {
            return;
        }
        for (TornStockPortfolioSlotDO slot : slots) {
            if (slot != null && slot.getId() != null) {
                slotsById.putIfAbsent(slot.getId(), slot);
            }
        }
    }

    /**
     * 合并多来源活跃批次,按主键去重。
     *
     * @param first  已合并活跃批次
     * @param second 待合并活跃批次
     * @return 去重后的活跃批次
     */
    private List<TornStockVirtualBatchDO> mergeActiveBatches(
            List<TornStockVirtualBatchDO> first,
            List<TornStockVirtualBatchDO> second) {
        Map<Long, TornStockVirtualBatchDO> batchesById = new LinkedHashMap<>();
        addBatchesById(batchesById, first);
        addBatchesById(batchesById, second);
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
            round.setStartedAt(marketClock.now());
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
        TornStockMarketRoundDO round = roundFactory.createRound(
                roundTime, StockRoundStatusEnum.PROCESSING.getCode());
        round.setExpectedStockCount(snapshot.bars().size());
        round.setUsableStockCount(countStrategyReady(snapshot));
        round.setStartedAt(marketClock.now());
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
     * @param snapshot 轮次快照(含变更后的批次与槽位)
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
        round.setCompletedAt(marketClock.now());
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

}
