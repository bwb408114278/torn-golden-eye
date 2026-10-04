package pn.torn.goldeneye.torn.service.stocks.alert.monthly;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMonthlyStateStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 股票月度风格状态初始化服务 - 按冻结月度公式为全部股票生成风格/成熟度/风险等级草稿记录
 * <p>
 * 生产入口为启动补偿编排 {@link #refreshCurrentMonthStates()}: 单事务内一次完成当月
 * 缺失初始化、未确认DRAFT重算与系统自动确认; {@link #initMonth(LocalDate)} 与
 * {@link #recalculateMonthDrafts(LocalDate)} 供历史范围回补指令按月正序调用。
 * 计算完全委托给 {@link StockMonthlyStateCalculator},
 * 使用冻结版本 {@value StockMonthlyStateCalculator#PERSONALITY_RULE_VERSION} 与
 * {@value StockMonthlyStateCalculator#RISK_RULE_VERSION},不再读取
 * {@code sys_setting.STOCK_PERSONALITY} 配置,也不再统一风险NONE。
 * <p>
 * 确认语义(1.8.0 复活精简版只保留系统自动确认,人工确认入口不取回):
 * <ul>
 *   <li>{@link #autoConfirmDraftStates(LocalDate)}: 系统确认,仅当数据完整性、规则版本、
 *       无人工覆盖、指标快照{@code confirmable=true}与迟滞结果全部满足时写{@code confirmedBy=SYSTEM},
 *       否则保持DRAFT;</li>
 *   <li>{@link #refreshCurrentMonthStates()}: 启动补偿专用编排,单事务内按
 *       {@value #EVIDENCE_BATCH_SIZE} 支股票分片载入证据,一次完成初始化/重算/自动确认。</li>
 * </ul>
 * 幂等: 当月已存在任意有效状态(DRAFT/CONFIRMED/RETIRED)的股票跳过初始化,
 * 插入使用PostgreSQL {@code ON CONFLICT DO NOTHING},与数据库部分唯一索引
 * {@code uk_stock_monthly_state_stock_month} 保持一致,重复启动/并发不抛异常、不重复、不覆盖。
 * 证据不完整的股票保持DRAFT且{@code strategyFitPrior/riskLevel}为空,禁止默认STEADY/NONE。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockMonthlyStateInitService {

    /**
     * 证据按股票分片的批大小(35支股票约5批,限制单次载入的365天可用bar行数)
     */
    static final int EVIDENCE_BATCH_SIZE = 8;

    private final TornStocksDAO tornStocksDao;
    private final TornStockMonthlyStateDAO monthlyStateDao;
    private final TornStockMarketBar15mDAO bar15mDao;
    private final StockMonthlyStateCalculator calculator;
    private final StockMarketClock marketClock;

    // ==================== 对外方法 ====================

    /**
     * 为指定生效月份初始化全部缺失股票的 DRAFT 月度状态。
     * <p>
     * 执行以下流程:
     * <ol>
     *   <li>获取全部股票列表</li>
     *   <li>一次查询该月任意有效状态(DRAFT/CONFIRMED/RETIRED)的股票ID集合,
     *       这些股票全部跳过初始化,避免与数据库部分唯一索引
     *       {@code uk_stock_monthly_state_stock_month} 冲突(幂等保护)</li>
     *   <li>一次查询该月已CONFIRMED股票ID集合,仅用于可观测日志</li>
     *   <li>批量加载缺失股票的证据窗口(首尾可用bar)与窗口内可用bar,避免N+1</li>
     *   <li>批量加载每支缺失股票最近更早CONFIRMED月度状态(迟滞参考)</li>
     *   <li>委托 {@link StockMonthlyStateCalculator} 计算草稿,证据不完整保持DRAFT且风格/风险为空</li>
     *   <li>使用PostgreSQL冲突安全批量插入(ON CONFLICT DO NOTHING),返回实际插入数</li>
     * </ol>
     *
     * @param effectiveMonth 目标生效月份（当月 1 日）
     * @return 本次初始化实际新建的草稿记录数量;全部股票已有有效状态时返回0
     */
    @Transactional
    public int initMonth(LocalDate effectiveMonth) {
        return initMonthInternal(effectiveMonth);
    }

    /**
     * 初始化指定月份缺失 DRAFT 状态的实际实现。
     * <p>
     * {@link #initMonth(LocalDate)} 的事务内实现,事务由公开入口的
     * {@code @Transactional} 经Spring代理提供,私有方法不重复标注。
     *
     * @param effectiveMonth 目标生效月份（当月 1 日）
     * @return 本次实际新建的草稿记录数量
     */
    private int initMonthInternal(LocalDate effectiveMonth) {
        List<TornStocksDO> allStocks = tornStocksDao.list();
        if (CollectionUtils.isEmpty(allStocks)) {
            log.warn("月度状态初始化-股票列表为空,跳过, effectiveMonth={}", effectiveMonth);
            return 0;
        }

        Set<Integer> existingStockIds = loadExistingStockIds(effectiveMonth);
        Set<Integer> confirmedStockIds = loadConfirmedStockIds(effectiveMonth);
        List<TornStocksDO> missingStocks = allStocks.stream()
                .filter(stock -> !existingStockIds.contains(stock.getId()))
                .toList();
        if (missingStocks.isEmpty()) {
            log.info("月度状态初始化-当月[{}]全部{}支股票已有任意有效状态,无需初始化, existingCount={}, confirmedCount={}",
                    effectiveMonth, allStocks.size(), existingStockIds.size(), confirmedStockIds.size());
            return 0;
        }

        List<Integer> missingStockIds = missingStocks.stream().map(TornStocksDO::getId).toList();
        EvidenceContext evidence = loadEvidenceContext(missingStockIds, effectiveMonth);

        List<TornStockMonthlyStateDO> draftStates = missingStocks.stream()
                .map(stock -> buildDraftState(stock, effectiveMonth,
                        evidence.evidenceEdges().get(stock.getId()),
                        evidence.barsByStock().getOrDefault(stock.getId(), List.of()),
                        evidence.previousByStock().get(stock.getId()),
                        evidence.now()))
                .toList();

        if (draftStates.isEmpty()) {
            log.info("月度状态初始化-当月[{}]无待初始化股票", effectiveMonth);
            return 0;
        }

        int insertedCount = monthlyStateDao.insertDraftStatesIgnoreConflict(draftStates);
        int conflictIgnoredCount = draftStates.size() - insertedCount;
        log.info("月度状态初始化-完成, effectiveMonth={}, existingCount={}, confirmedCount={}, "
                        + "candidateCount={}, insertedCount={}, conflictIgnoredCount={}",
                effectiveMonth, existingStockIds.size(), confirmedStockIds.size(),
                draftStates.size(), insertedCount, conflictIgnoredCount);
        return insertedCount;
    }

    /**
     * 重算指定生效月份中未确认且非人工覆盖的 DRAFT 月度状态。
     * <p>
     * 与 {@link #initMonth(LocalDate)} 互补: 后者只负责为缺失股票初始化DRAFT行,
     * 本方法只重算该月已存在 {@code state_status=DRAFT} 且
     * {@code manual_override=false} 的记录。数据补齐后再次调用即可让空DRAFT升级为
     * 完整机器建议,不再因{@code initMonth(LocalDate)}的"已存在即跳过"语义永久阻塞。
     * 供历史范围回补按月正序调用。
     * <p>
     * 约束:
     * <ul>
     *   <li>仅更新 {@code state_status=DRAFT AND manual_override=false},数据库UPDATE自带该谓词,
     *       任何CONFIRMED/RETIRED或人工覆盖记录均不得被覆盖、降级或改写confirmedBy/confirmedAt;</li>
     *   <li>计算输入复用现有批量证据查询与{@link #loadPreviousByStocks(List, LocalDate)},
     *       不引入每股票N+1;</li>
     *   <li>幂等: 相同证据重复重算结果稳定。</li>
     * </ul>
     *
     * @param effectiveMonth 目标生效月份（当月 1 日）
     * @return 本次实际更新的 DRAFT 记录数量
     */
    @Transactional
    public int recalculateMonthDrafts(LocalDate effectiveMonth) {
        return recalculateMonthDraftsInternal(effectiveMonth);
    }

    /**
     * 重算指定月份 DRAFT 状态的实际实现。
     * <p>
     * {@link #recalculateMonthDrafts(LocalDate)} 的事务内实现,事务由公开入口的
     * {@code @Transactional} 经Spring代理提供,私有方法不重复标注。
     *
     * @param effectiveMonth 目标生效月份（当月 1 日）
     * @return 本次实际更新的 DRAFT 记录数量
     */
    private int recalculateMonthDraftsInternal(LocalDate effectiveMonth) {
        List<TornStockMonthlyStateDO> drafts = monthlyStateDao.lambdaQuery()
                .eq(TornStockMonthlyStateDO::getEffectiveMonth, effectiveMonth)
                .eq(TornStockMonthlyStateDO::getStateStatus, StockMonthlyStateStatusEnum.DRAFT.getCode())
                .eq(TornStockMonthlyStateDO::getManualOverride, false)
                .list();
        if (CollectionUtils.isEmpty(drafts)) {
            log.info("月度状态重算-当月[{}]无未确认非人工覆盖DRAFT,跳过", effectiveMonth);
            return 0;
        }

        List<Integer> stockIds = drafts.stream()
                .map(TornStockMonthlyStateDO::getStocksId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<TornStocksDO> stocks = tornStocksDao.listByIds(stockIds);
        Map<Integer, TornStocksDO> stockById = stocks.stream()
                .filter(stock -> stock.getId() != null)
                .collect(Collectors.toMap(TornStocksDO::getId, stock -> stock, (left, right) -> left));

        EvidenceContext evidence = loadEvidenceContext(stockIds, effectiveMonth);
        LocalDateTime now = evidence.now();
        List<TornStockMonthlyStateDO> recalculated = new ArrayList<>();
        for (TornStockMonthlyStateDO draft : drafts) {
            TornStocksDO stock = stockById.get(draft.getStocksId());
            if (stock == null) {
                log.warn("月度状态重算-股票[{}]不存在,跳过该DRAFT", draft.getStocksId());
                continue;
            }
            TornStockMonthlyStateDO updated = buildDraftState(stock, effectiveMonth,
                    evidence.evidenceEdges().get(draft.getStocksId()),
                    evidence.barsByStock().getOrDefault(draft.getStocksId(), List.of()),
                    evidence.previousByStock().get(draft.getStocksId()), now);
            updated.setId(draft.getId());
            recalculated.add(updated);
        }
        if (recalculated.isEmpty()) {
            log.info("月度状态重算-当月[{}]无有效可重算股票,返回0", effectiveMonth);
            return 0;
        }

        int updatedCount = monthlyStateDao.recalculateDraftStates(recalculated);
        log.info("月度状态重算-完成, effectiveMonth={}, 重算候选={}, 实际更新={}",
                effectiveMonth, recalculated.size(), updatedCount);
        return updatedCount;
    }

    /**
     * 系统自动确认指定月份满足冻结条件的草稿状态。
     * <p>
     * 仅当数据完整性、规则版本、无人工覆盖且已生成迟滞结果时写
     * {@code confirmedBy=SYSTEM};任一条件不满足的草稿继续保持DRAFT。
     * <p>
     * 落库为条件UPDATE({@code state_status='DRAFT' AND manual_override=false AND deleted=0}),
     * 返回实际受影响行数:{@link #isAutoConfirmable} 仅为Java预过滤(减少候选行),数据库自带状态谓词
     * 才是最终守卫。若并发状态变更在SELECT与UPDATE之间抢占了记录(0行受影响),
     * 不得重试或覆盖,直接保持既有结果。
     *
     * @param effectiveMonth 生效月份
     * @return 本次实际自动确认的记录数量(数据库实际受影响行数,非候选数量)
     */
    @Transactional
    public int autoConfirmDraftStates(LocalDate effectiveMonth) {
        return autoConfirmDraftStatesInternal(effectiveMonth);
    }

    /**
     * 系统自动确认指定月份的实际实现。
     * <p>
     * 供 {@link #autoConfirmDraftStates(LocalDate)} 与 {@link #refreshCurrentMonthStates()} 共用，
     * 避免事务方法通过 {@code this} 自调用导致 Spring 代理事务失效。
     *
     * @param effectiveMonth 生效月份
     * @return 本次实际自动确认的记录数量(数据库实际受影响行数,非候选数量)
     */
    private int autoConfirmDraftStatesInternal(LocalDate effectiveMonth) {
        List<TornStockMonthlyStateDO> draftStates = monthlyStateDao.lambdaQuery()
                .eq(TornStockMonthlyStateDO::getEffectiveMonth, effectiveMonth)
                .eq(TornStockMonthlyStateDO::getStateStatus, StockMonthlyStateStatusEnum.DRAFT.getCode())
                .list();
        if (CollectionUtils.isEmpty(draftStates)) {
            log.info("月度状态自动确认-当月[{}]无DRAFT记录,跳过", effectiveMonth);
            return 0;
        }

        LocalDateTime now = marketClock.now();
        List<TornStockMonthlyStateDO> confirmableStates = new ArrayList<>();
        for (TornStockMonthlyStateDO state : draftStates) {
            if (!isAutoConfirmable(state)) {
                log.info("月度状态自动确认-记录不满足自动确认条件,保留DRAFT: stocksId={}, effectiveMonth={}",
                        state.getStocksId(), effectiveMonth);
                continue;
            }
            state.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
            state.setConfirmedAt(now);
            state.setConfirmedBy("SYSTEM");
            confirmableStates.add(state);
        }
        if (confirmableStates.isEmpty()) {
            log.warn("月度状态自动确认-没有满足自动确认条件的DRAFT, effectiveMonth={}", effectiveMonth);
            return 0;
        }
        int confirmedCount = monthlyStateDao.autoConfirmDraftStates(confirmableStates);
        log.info("月度状态自动确认-完成, effectiveMonth={}, 自动确认候选={}, 实际确认={}",
                effectiveMonth, confirmableStates.size(), confirmedCount);
        return confirmedCount;
    }

    /**
     * 启动补偿专用月度编排: 单事务内一次完成当月缺失初始化、未确认DRAFT重算与系统自动确认。
     * <p>
     * 相对分别调用 {@link #initMonth(LocalDate)} + {@link #recalculateMonthDrafts(LocalDate)} +
     * {@link #autoConfirmDraftStates(LocalDate)} 的改进(1.8.0 Review §4.7):
     * <ul>
     *   <li>一次求出「当月缺失股票 ∪ 当月未确认非人工覆盖DRAFT股票」目标集合,
     *       365天证据bar只按 {@link #EVIDENCE_BATCH_SIZE} 分片载入,峰值内存由整表常驻
     *       降为单片,大查询由两轮降为按批IN查询;</li>
     *   <li>全部草稿共用同一 {@code calculatedAt}(单次读取时钟);</li>
     *   <li>插入与重算各自累积后批量执行一次,最后统一自动确认;</li>
     *   <li>幂等语义与分步方法完全一致(ON CONFLICT DO NOTHING、DRAFT+无人工覆盖谓词)。</li>
     * </ul>
     *
     * @return 编排结果计数
     */
    @Transactional
    public MonthlyRefreshResult refreshCurrentMonthStates() {
        LocalDate effectiveMonth = marketClock.today().withDayOfMonth(1);
        List<TornStocksDO> allStocks = tornStocksDao.list();
        if (CollectionUtils.isEmpty(allStocks)) {
            log.warn("月度状态编排-股票列表为空,跳过, effectiveMonth={}", effectiveMonth);
            return new MonthlyRefreshResult(0, 0, autoConfirmDraftStatesInternal(effectiveMonth));
        }

        Set<Integer> existingStockIds = loadExistingStockIds(effectiveMonth);
        Map<Integer, TornStocksDO> stockById = allStocks.stream()
                .filter(stock -> stock.getId() != null)
                .collect(Collectors.toMap(TornStocksDO::getId, stock -> stock, (left, right) -> left));
        Map<Integer, TornStockMonthlyStateDO> draftByStock = monthlyStateDao.lambdaQuery()
                .eq(TornStockMonthlyStateDO::getEffectiveMonth, effectiveMonth)
                .eq(TornStockMonthlyStateDO::getStateStatus, StockMonthlyStateStatusEnum.DRAFT.getCode())
                .eq(TornStockMonthlyStateDO::getManualOverride, false)
                .list().stream()
                .filter(draft -> draft != null && draft.getStocksId() != null)
                .collect(Collectors.toMap(TornStockMonthlyStateDO::getStocksId, draft -> draft,
                        (left, right) -> left));

        // 目标集合 = 当月缺失股票(初始化) ∪ 当月未确认非人工覆盖DRAFT股票(重算)
        Set<Integer> targetStockIds = new LinkedHashSet<>(stockById.keySet());
        targetStockIds.removeAll(existingStockIds);
        targetStockIds.addAll(draftByStock.keySet());

        LocalDateTime now = marketClock.now();
        List<TornStockMonthlyStateDO> toInsert = new ArrayList<>();
        List<TornStockMonthlyStateDO> toRecalculate = new ArrayList<>();
        List<Integer> targetIds = List.copyOf(targetStockIds);
        for (int i = 0; i < targetIds.size(); i += EVIDENCE_BATCH_SIZE) {
            List<Integer> shard = targetIds.subList(i, Math.min(i + EVIDENCE_BATCH_SIZE, targetIds.size()));
            EvidenceContext evidence = loadEvidenceContext(shard, effectiveMonth, now);
            for (Integer stocksId : shard) {
                TornStocksDO stock = stockById.get(stocksId);
                if (stock == null) {
                    log.warn("月度状态编排-股票[{}]不存在,跳过", stocksId);
                    continue;
                }
                TornStockMonthlyStateDO updated = buildDraftState(stock, effectiveMonth,
                        evidence.evidenceEdges().get(stocksId),
                        evidence.barsByStock().getOrDefault(stocksId, List.of()),
                        evidence.previousByStock().get(stocksId), now);
                TornStockMonthlyStateDO existingDraft = draftByStock.get(stocksId);
                if (existingDraft == null) {
                    toInsert.add(updated);
                } else {
                    updated.setId(existingDraft.getId());
                    toRecalculate.add(updated);
                }
            }
        }

        int insertedCount = toInsert.isEmpty() ? 0 : monthlyStateDao.insertDraftStatesIgnoreConflict(toInsert);
        int recalculatedCount = toRecalculate.isEmpty() ? 0 : monthlyStateDao.recalculateDraftStates(toRecalculate);
        int confirmedCount = autoConfirmDraftStatesInternal(effectiveMonth);
        log.info("月度状态编排-完成, effectiveMonth={}, inserted={}, recalculated={}, confirmed={}",
                effectiveMonth, insertedCount, recalculatedCount, confirmedCount);
        return new MonthlyRefreshResult(insertedCount, recalculatedCount, confirmedCount);
    }

    /**
     * 校验月度状态是否满足CONFIRMED落库完整性要求。
     *
     * @param state 待确认状态
     * @return 满足完整性要求返回true
     */
    private boolean isConfirmable(TornStockMonthlyStateDO state) {
        return state.getStrategyFitPrior() != null && !state.getStrategyFitPrior().isBlank()
                && state.getMaturity() != null && !state.getMaturity().isBlank()
                && state.getRiskLevel() != null && !state.getRiskLevel().isBlank()
                && state.getSuggestedPersonality() != null && !state.getSuggestedPersonality().isBlank()
                && state.getEvidenceStartTime() != null
                && state.getEvidenceEndTime() != null
                && !state.getEvidenceStartTime().isAfter(state.getEvidenceEndTime());
    }

    /**
     * 校验月度状态是否满足系统自动确认条件。
     * <p>
     * 在落库完整性之上额外校验指标快照的 {@code confirmable} 键(设计§4.3条件5/6的
     * 机器结论):仅当快照存在且值为 {@code true} 才允许确认;键缺失、解析失败或非布尔
     * 一律视为false(fail-closed),防止"完整但迟滞未就绪"的DRAFT被确认。
     *
     * @param state 待自动确认状态
     * @return 满足自动确认条件返回true
     */
    private boolean isAutoConfirmable(TornStockMonthlyStateDO state) {
        return isConfirmable(state)
                && !Boolean.TRUE.equals(state.getManualOverride())
                && StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION.equals(state.getPersonalityRuleVersion())
                && StockMonthlyStateCalculator.RISK_RULE_VERSION.equals(state.getRiskRuleVersion())
                && isSnapshotConfirmable(state.getMetricSnapshot());
    }

    /**
     * 解析指标快照的 {@code confirmable} 键(fail-closed)。
     *
     * @param metricSnapshot 指标快照JSON文本
     * @return 仅当键存在且为布尔true时返回true;空/缺失/非法一律false
     */
    private boolean isSnapshotConfirmable(String metricSnapshot) {
        if (metricSnapshot == null || metricSnapshot.isBlank()) {
            return false;
        }
        try {
            JsonNode node = JsonUtils.getNode(metricSnapshot,
                    StockMonthlyStateCalculator.SNAPSHOT_KEY_CONFIRMABLE);
            return node != null && node.isBoolean() && node.asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 私有方法: 草稿构建 ====================

    /**
     * 为单支股票按冻结公式构建DRAFT状态的月度状态记录。
     *
     * @param stock          股票DO
     * @param effectiveMonth 生效月份
     * @param evidenceEdge   证据首尾bar时间(可为null)
     * @param usableBars     证据窗口内可用bar列表(可为空)
     * @param previous       上一确认月度状态(可为null)
     * @param now            计算时间
     * @return 初始化完成的草稿DO;证据不完整时仍返回DRAFT且风格/风险为空(fail-closed)
     */
    private TornStockMonthlyStateDO buildDraftState(TornStocksDO stock,
                                                    LocalDate effectiveMonth,
                                                    TornStockMarketBar15mDO evidenceEdge,
                                                    List<TornStockMarketBar15mDO> usableBars,
                                                    TornStockMonthlyStateDO previous,
                                                    LocalDateTime now) {
        LocalDateTime evidenceEnd = null;
        if (evidenceEdge != null && evidenceEdge.getBarEndTime() != null) {
            evidenceEnd = evidenceEdge.getBarEndTime();
        }
        LocalDateTime evidenceStart = computeEvidenceStart(evidenceEdge, evidenceEnd);

        StockMonthlyPrevious previousRef = calculator.parsePrevious(previous);
        StockMonthlyStateDraft draft = calculator.calculate(
                stock.getId(), stock.getStocksShortname(), effectiveMonth,
                evidenceStart, evidenceEnd, usableBars, previousRef);

        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(draft.stocksId());
        state.setStocksShortname(draft.stocksShortname());
        state.setEffectiveMonth(draft.effectiveMonth());
        state.setStrategyFitPrior(draft.strategyFitPrior() == null ? null : draft.strategyFitPrior().getCode());
        state.setMaturity(draft.maturity().getCode());
        state.setRiskLevel(draft.riskLevel() == null ? null : draft.riskLevel().getCode());
        state.setSuggestedPersonality(
                draft.suggestedPersonality() == null ? null : draft.suggestedPersonality().getCode());
        state.setPreviousPersonality(
                draft.previousPersonality() == null ? null : draft.previousPersonality().getCode());
        state.setManualOverride(false);
        state.setOverrideReason(null);
        state.setMetricSnapshot(draft.metricSnapshot());
        state.setPersonalityRuleVersion(StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION);
        state.setRiskRuleVersion(StockMonthlyStateCalculator.RISK_RULE_VERSION);
        state.setEvidenceStartTime(draft.evidenceStartTime());
        state.setEvidenceEndTime(draft.evidenceEndTime());
        state.setStateStatus(StockMonthlyStateStatusEnum.DRAFT.getCode());
        state.setCalculatedAt(now);
        state.setConfirmedAt(null);
        state.setConfirmedBy(null);
        return state;
    }

    /**
     * 计算证据起点: 股票首个可用bar时间与(证据终点-365天)的较晚值。
     *
     * @param evidenceEdge 证据首尾bar时间(可为null)
     * @param evidenceEnd  证据终点(可为null)
     * @return 证据起点;无证据终点时返回null
     */
    private LocalDateTime computeEvidenceStart(TornStockMarketBar15mDO evidenceEdge,
                                               LocalDateTime evidenceEnd) {
        if (evidenceEnd == null) {
            return null;
        }
        LocalDateTime windowStart = evidenceEnd.minusDays(StockMonthlyStateCalculator.MAX_EVIDENCE_DAYS);
        if (evidenceEdge == null || evidenceEdge.getFirstSampleTime() == null) {
            return windowStart;
        }
        LocalDateTime firstBar = evidenceEdge.getFirstSampleTime();
        return firstBar.isAfter(windowStart) ? firstBar : windowStart;
    }

    // ==================== 私有方法: 数据加载 ====================

    /**
     * 批量加载证据上下文: 计算时间、证据首尾bar、证据窗口bar与上一确认月度状态。
     * <p>
     * 初始化与重算共用同一批证据装载语义,避免重复代码;单次计算时间戳保证
     * 批量内所有草稿的 {@code calculatedAt} 一致。
     *
     * @param stockIds       股票ID列表
     * @param effectiveMonth 生效月份
     * @return 证据上下文(计算时间+三份证据映射)
     */
    private EvidenceContext loadEvidenceContext(List<Integer> stockIds, LocalDate effectiveMonth) {
        return loadEvidenceContext(stockIds, effectiveMonth, marketClock.now());
    }

    /**
     * 批量加载证据上下文(调用方提供统一计算时间)。
     * <p>
     * 供 {@link #refreshCurrentMonthStates()} 分片复用同一 {@code now},
     * 保证跨批次全部草稿的 {@code calculatedAt} 全局一致。
     *
     * @param stockIds       股票ID列表
     * @param effectiveMonth 生效月份
     * @param now            本批共用的计算时间戳
     * @return 证据上下文(计算时间+三份证据映射)
     */
    private EvidenceContext loadEvidenceContext(List<Integer> stockIds, LocalDate effectiveMonth,
                                                LocalDateTime now) {
        return new EvidenceContext(
                now,
                loadEvidenceEdges(stockIds, effectiveMonth),
                loadEvidenceBars(stockIds, effectiveMonth),
                loadPreviousByStocks(stockIds, effectiveMonth));
    }

    /**
     * 批量加载每支股票证据首尾bar时间。
     *
     * @param stockIds       股票ID列表
     * @param effectiveMonth 生效月份
     * @return 股票ID到证据首尾bar时间的映射
     */
    private Map<Integer, TornStockMarketBar15mDO> loadEvidenceEdges(List<Integer> stockIds,
                                                                    LocalDate effectiveMonth) {
        List<TornStockMarketBar15mDO> edges = bar15mDao.selectUsableEvidenceEdges(
                stockIds, effectiveMonth.atStartOfDay(), Stock15mBarBuildService.BUILD_VERSION);
        if (CollectionUtils.isEmpty(edges)) {
            return Map.of();
        }
        return edges.stream()
                .filter(edge -> edge.getStocksId() != null)
                .collect(Collectors.toMap(TornStockMarketBar15mDO::getStocksId,
                        value -> value, (left, right) -> left, HashMap::new));
    }

    /**
     * 批量加载每支股票证据窗口内的可用bar。
     *
     * @param stockIds       股票ID列表
     * @param effectiveMonth 生效月份
     * @return 股票ID到可用bar列表(按时间升序)的映射
     */
    private Map<Integer, List<TornStockMarketBar15mDO>> loadEvidenceBars(List<Integer> stockIds,
                                                                         LocalDate effectiveMonth) {
        LocalDateTime evidenceEnd = effectiveMonth.atStartOfDay().minusMinutes(15);
        LocalDateTime evidenceStart = evidenceEnd.minusDays(StockMonthlyStateCalculator.MAX_EVIDENCE_DAYS);
        List<TornStockMarketBar15mDO> bars = bar15mDao.selectUsableByStocksAndTimeRange(
                stockIds, evidenceStart, evidenceEnd, Stock15mBarBuildService.BUILD_VERSION);
        if (CollectionUtils.isEmpty(bars)) {
            return Map.of();
        }
        return bars.stream()
                .filter(bar -> bar.getStocksId() != null)
                .collect(Collectors.groupingBy(
                        TornStockMarketBar15mDO::getStocksId, HashMap::new,
                        Collectors.toList()));
    }

    /**
     * 批量加载每支股票最近更早CONFIRMED月度状态(迟滞参考,严格版本隔离)。
     * <p>
     * 传入当前V2双规则版本做SQL精确过滤,2026-03首月无更早V2 CONFIRMED时
     * previous=null;V1 CONFIRMED不作为V2迟滞输入,SQL侧不得回退任意旧版本。
     *
     * @param stockIds       股票ID列表
     * @param effectiveMonth 生效月份
     * @return 股票ID到上一确认月度状态的映射
     */
    private Map<Integer, TornStockMonthlyStateDO> loadPreviousByStocks(List<Integer> stockIds,
                                                                       LocalDate effectiveMonth) {
        List<TornStockMonthlyStateDO> previous = monthlyStateDao.selectPreviousConfirmedByStocks(
                stockIds, effectiveMonth,
                StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION,
                StockMonthlyStateCalculator.RISK_RULE_VERSION);
        if (CollectionUtils.isEmpty(previous)) {
            return Map.of();
        }
        return previous.stream()
                .filter(state -> state.getStocksId() != null)
                .collect(Collectors.toMap(TornStockMonthlyStateDO::getStocksId,
                        value -> value, (left, right) -> left, HashMap::new));
    }

    /**
     * 加载当月存在任意有效状态(DRAFT/CONFIRMED/RETIRED)的股票ID集合
     * <p>
     * 用于初始化幂等过滤:同月每股票至多一行有效状态,只要已存在任意状态,
     * 都不得再INSERT该股票,避免触发数据库部分唯一索引冲突。
     *
     * @param effectiveMonth 生效月份
     * @return 当月已有任意有效状态的股票ID集合;无记录时返回空Set
     */
    private Set<Integer> loadExistingStockIds(LocalDate effectiveMonth) {
        List<Integer> existingIds = monthlyStateDao.selectExistingStockIdsByMonth(effectiveMonth);
        if (CollectionUtils.isEmpty(existingIds)) {
            return Set.of();
        }
        return new HashSet<>(existingIds);
    }

    /**
     * 加载当月已CONFIRMED状态的股票ID集合
     * <p>
     * 仅用于可观测日志,不作为初始化INSERT过滤条件;初始化过滤使用
     * {@link #loadExistingStockIds(LocalDate)}。
     *
     * @param effectiveMonth 生效月份
     * @return 已确认股票ID集合;无记录时返回空Set
     */
    private Set<Integer> loadConfirmedStockIds(LocalDate effectiveMonth) {
        List<TornStockMonthlyStateDO> confirmed = monthlyStateDao.selectConfirmedByMonth(effectiveMonth);
        if (CollectionUtils.isEmpty(confirmed)) {
            return Set.of();
        }
        return confirmed.stream()
                .map(TornStockMonthlyStateDO::getStocksId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 证据上下文 - 封装一次批量初始化/重算所需的计算时间与三份证据映射。
     *
     * @param now             批量计算时间戳
     * @param evidenceEdges   股票ID到证据首尾bar的映射
     * @param barsByStock     股票ID到证据窗口内可用bar的映射
     * @param previousByStock 股票ID到上一确认月度状态的映射
     */
    private record EvidenceContext(
            LocalDateTime now,
            Map<Integer, TornStockMarketBar15mDO> evidenceEdges,
            Map<Integer, List<TornStockMarketBar15mDO>> barsByStock,
            Map<Integer, TornStockMonthlyStateDO> previousByStock
    ) {
    }

    /**
     * 月度编排结果 - {@link #refreshCurrentMonthStates()} 的执行计数。
     *
     * @param insertedCount     本次实际初始化插入的草稿数
     * @param recalculatedCount 本次实际重算更新的DRAFT数
     * @param confirmedCount    本次实际自动确认的记录数
     */
    public record MonthlyRefreshResult(
            int insertedCount,
            int recalculatedCount,
            int confirmedCount) {
    }
}
