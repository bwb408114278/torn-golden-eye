package pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution.StockAlphaExecutionBarPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaDailyCloseCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaDailyCloseService;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockHashUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * α策略日线决策服务。
 *
 * <p>本服务只生成可追溯的日线决策记录，不创建交易批次、不扣减资金且不发送通知。</p>
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlphaDecisionService {
    private static final String PENDING_STATUS = "PENDING";

    private final StockAlphaDailyCloseService dailyCloseService;
    private final TornStockAlphaDecisionDAO decisionDAO;

    /**
     * 根据指定日期和执行桶生成或读取唯一α决策。
     *
     * @param decisionDate      决策日期
     * @param executionBarStart 固定执行bar起点
     * @return 已持久化决策
     */
    public DecisionResult decide(LocalDate decisionDate, LocalDateTime executionBarStart) {
        return decideInternal(decisionDate, null, null, executionBarStart);
    }

    /**
     * 根据日期、持仓上下文和固定执行桶生成或读取唯一α决策。
     *
     * @param decisionDate      决策日期
     * @param currentStocksId   当前持仓股票ID
     * @param currentBatchId    当前持仓批次ID
     * @param executionBarStart 固定执行bar起点
     * @return 已持久化决策
     */
    public DecisionResult decide(LocalDate decisionDate, Integer currentStocksId, Long currentBatchId,
                                 LocalDateTime executionBarStart) {
        return decideInternal(decisionDate, currentStocksId, currentBatchId, executionBarStart);
    }

    /**
     * 按共同有效日和持仓上下文生成或读取唯一决策。
     * <p>
     * 执行bar起点由调用方以轮次执行桶显式传入并经{@link StockAlphaExecutionBarPolicy}校验后持久化,
     * 执行阶段只消费该持久化值,不得再由轮次时间反推。
     *
     * @param decisionDate      决策日期
     * @param currentStocksId   当前持仓股票ID
     * @param currentBatchId    当前持仓批次ID
     * @param executionBarStart 固定执行bar起点
     * @return 已持久化决策
     */
    private DecisionResult decideInternal(LocalDate decisionDate, Integer currentStocksId, Long currentBatchId,
                                          LocalDateTime executionBarStart) {
        Objects.requireNonNull(decisionDate, "决策日期不能为空");
        Objects.requireNonNull(executionBarStart, "执行bar起点不能为空");
        LocalDateTime executionBar = StockAlphaExecutionBarPolicy.requireExecutionBar(executionBarStart);
        Calculation calculation = calculate(decisionDate, currentStocksId);
        if (!calculation.ready()) {
            return notReady(calculation, executionBar);
        }
        int phase = phaseOf(calculation.commonDayCount());
        DecisionResult candidate = new DecisionResult(calculation.decisionDate(), true, calculation.commonDayCount(),
                calculation.rankings(), calculation.targetStocksId(), calculation.event(), phase, executionBar);
        TornStockAlphaDecisionDO existing = decisionDAO.selectByBusinessKeyForUpdate(calculation.decisionDate(), phase);
        if (existing != null) {
            return resolveExistingDecision(existing, calculation, candidate, phase, executionBar);
        }
        TornStockAlphaDecisionDO decision = toDecisionDO(candidate, phase, currentBatchId, executionBar);
        if (decisionDAO.insertIgnoreConflict(decision) != 1) {
            TornStockAlphaDecisionDO concurrent =
                    decisionDAO.selectByBusinessKeyForUpdate(calculation.decisionDate(), phase);
            if (concurrent != null) {
                return resolveExistingDecision(concurrent, calculation, candidate, phase, executionBar);
            }
            log.warn("α决策插入未生效且未读到并发决策,本次不消费phase: decisionDate={}, phase={}",
                    calculation.decisionDate(), phase);
            return notReady(calculation, executionBar);
        }
        dailyCloseService.persistRankings(calculation.decisionDate(), calculation.latestCloses(),
                calculation.rankings());
        return toDecisionResult(decision, calculation.rankings());
    }

    /**
     * 构造不消费phase的未就绪决策结果。
     *
     * @param calculation  本次计算结果
     * @param executionBar 本轮执行bar起点
     * @return ready=false且不携带目标与phase的决策结果
     */
    private DecisionResult notReady(Calculation calculation, LocalDateTime executionBar) {
        return new DecisionResult(calculation.decisionDate(), false, calculation.commonDayCount(), null, null,
                calculation.event(), null, executionBar);
    }

    /**
     * 复用或拒绝已持久化决策。
     * <p>
     * 已持久化决策是唯一事实:必须与本次计算的决策日、phase、决策类型和来源摘要完全一致;
     * 待消费(PENDING)决策只能由持久化执行桶所在的轮次消费。任一校验失败一律fail-closed返回未就绪,
     * 不创建、不覆盖任何决策,保证同一phase重试始终复用原决策和原执行桶。
     * 已完成决策只进入幂等短路:返回持久化决策日、phase、目标和执行桶,不回写本次计算结果,
     * 也不使用本次新算排名覆盖已成交事实。
     *
     * @param existing     已持久化决策
     * @param calculation  本次计算结果
     * @param candidate    本次计算的候选决策结果
     * @param phase        消费阶段
     * @param executionBar 本轮执行bar起点
     * @return 决策结果
     */
    private DecisionResult resolveExistingDecision(TornStockAlphaDecisionDO existing, Calculation calculation,
                                                   DecisionResult candidate, int phase,
                                                   LocalDateTime executionBar) {
        if (!isSameDecisionFact(existing, candidate, phase)) {
            log.warn("α已持久化决策与本次计算事实不一致,本次不消费: decisionId={}, decisionDate={}, phase={}, "
                            + "persistedType={}, persistedExecutionBar={}, roundExecutionBar={}",
                    existing.getId(), candidate.decisionDate(), phase, existing.getDecisionType(),
                    existing.getExecutionBarStartTime(), executionBar);
            return notReady(calculation, executionBar);
        }
        boolean sameExecutionBar = executionBar.equals(existing.getExecutionBarStartTime());
        if (PENDING_STATUS.equals(existing.getExecutionStatus()) && !sameExecutionBar) {
            log.warn("α待消费决策执行桶与当前轮次不一致,本次不消费: decisionId={}, persistedExecutionBar={}, "
                            + "roundExecutionBar={}", existing.getId(), existing.getExecutionBarStartTime(),
                    executionBar);
            return notReady(calculation, executionBar);
        }
        return toDecisionResult(existing, sameExecutionBar ? candidate.rankings() : List.of());
    }

    /**
     * 判断已持久化决策与本次计算是否属于同一决策事实。
     *
     * @param existing  已持久化决策
     * @param candidate 本次计算的候选决策结果
     * @param phase     消费阶段
     * @return 决策日、phase、决策类型和来源摘要全部一致时返回true
     */
    private boolean isSameDecisionFact(TornStockAlphaDecisionDO existing, DecisionResult candidate, int phase) {
        return candidate.decisionDate().equals(existing.getDecisionBusinessDate())
                && Integer.valueOf(phase).equals(existing.getPhase())
                && candidate.event().name().equals(existing.getDecisionType())
                && buildSourceSnapshotDigest(candidate).equals(existing.getSourceSnapshotDigest());
    }

    /**
     * 按共同有效日计算消费阶段。
     *
     * @param commonDayCount 共同有效日序号
     * @return phase编号
     */
    private int phaseOf(int commonDayCount) {
        return (commonDayCount - StockAlphaRuleDefinition.WARMUP_COMMON_DAYS)
                / StockAlphaRuleDefinition.DECISION_INTERVAL_DAYS;
    }

    /**
     * 将决策结果转换为持久化对象。
     *
     * @param result                待持久化的决策结果
     * @param phase                 消费阶段
     * @param currentBatchId        当前持仓批次ID;初始入场时为空
     * @param executionBarStartTime 固定执行bar起点
     * @return 决策持久化对象
     */
    private TornStockAlphaDecisionDO toDecisionDO(DecisionResult result, int phase, Long currentBatchId,
                                                  LocalDateTime executionBarStartTime) {
        TornStockAlphaDecisionDO decision = new TornStockAlphaDecisionDO();
        decision.setDecisionBusinessDate(result.decisionDate());
        decision.setCommonDayIndex(result.commonDayCount());
        decision.setPhase(phase);
        decision.setCurrentBatchId(currentBatchId);
        decision.setExecutionBarStartTime(executionBarStartTime);
        decision.setDecisionType(result.event().name());
        decision.setSourceSnapshotDigest(buildSourceSnapshotDigest(result));
        decision.setExecutionStatus(PENDING_STATUS);
        decision.setSelectedStocksId(result.targetStocksId());
        return decision;
    }

    private String buildSourceSnapshotDigest(DecisionResult result) {
        String source = result.decisionDate() + "|" + result.commonDayCount() + "|"
                + result.rankings().stream().sorted(Comparator.comparing(StockAlphaRankingResult::stocksId))
                .map(ranking -> ranking.stocksId() + ":" + ranking.r20() + ":" + ranking.r1() + ":"
                        + ranking.r20Rank() + ":" + ranking.r1Rank() + ":" + ranking.alphaScore() + ":"
                        + ranking.rankPosition()).collect(java.util.stream.Collectors.joining("|"));
        return StockHashUtils.sha256(StockAlphaRuleDefinition.RULE_VERSION + "|" + source);
    }

    /**
     * 将持久化决策转换为领域结果。
     * <p>
     * 日期、phase、目标、事件和执行桶全部取自持久化决策,不混入本次新算结果;
     * 只有来源摘要校验通过且执行桶一致时,排名才允许使用本次计算结果,避免两套事实混用。
     *
     * @param decision 决策持久化对象
     * @param rankings 本次计算排名;与持久化事实不对应时必须传空列表
     * @return 决策结果
     */
    private DecisionResult toDecisionResult(TornStockAlphaDecisionDO decision,
                                            List<StockAlphaRankingResult> rankings) {
        StockAlphaTargetPolicy.TargetEvent event = StockAlphaTargetPolicy.TargetEvent.valueOf(decision.getDecisionType());
        return new DecisionResult(decision.getDecisionBusinessDate(), true,
                decision.getCommonDayIndex(), rankings, decision.getSelectedStocksId(), event,
                decision.getPhase(), decision.getExecutionBarStartTime());
    }

    /**
     * 计算指定日期的α决策。
     * <p>
     * 只读取已完整持久化的日线快照并执行纯内存排名,不重写历史快照。
     *
     * @param decisionDate    决策日期
     * @param currentStocksId 当前持仓股票ID
     * @return 计算结果
     */
    private Calculation calculate(LocalDate decisionDate, Integer currentStocksId) {
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily =
                dailyCloseService.loadDailyCloses(decisionDate);
        List<LocalDate> commonDates = daily.entrySet().stream()
                .filter(entry -> entry.getValue().size() == StockAlphaRuleDefinition.MEMBER_COUNT
                        && entry.getValue().values().stream().allMatch(Objects::nonNull))
                .map(Map.Entry::getKey).sorted().toList();
        int commonDayCount = commonDates.size();
        if (commonDayCount < StockAlphaRuleDefinition.WARMUP_COMMON_DAYS
                || !isDecisionDay(commonDayCount)) {
            return new Calculation(decisionDate, false, commonDayCount, daily, List.of(), null,
                    StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT);
        }
        Map<Integer, List<BigDecimal>> closes = buildCloseSeries(commonDates, daily);
        List<StockAlphaRankingResult> rankings = StockAlphaRankingCalculator.calculate(closes);
        StockAlphaTargetPolicy.TargetResult target = StockAlphaTargetPolicy.decide(commonDayCount, rankings, currentStocksId);
        return new Calculation(commonDates.getLast(), true, commonDayCount, daily, rankings,
                target.targetStocksId(), target.event());
    }

    /**
     * 判断共同有效日数量是否为决策日。
     *
     * @param commonDayCount 共同有效日数量
     * @return 是否为决策日
     */
    private boolean isDecisionDay(int commonDayCount) {
        return (commonDayCount - StockAlphaRuleDefinition.WARMUP_COMMON_DAYS)
                % StockAlphaRuleDefinition.DECISION_INTERVAL_DAYS == 0;
    }

    /**
     * 按共同有效日期构建股票收盘序列。
     *
     * @param commonDates 共同有效日期
     * @param daily       日线收盘数据
     * @return 股票ID到收盘序列的映射
     */
    private Map<Integer, List<BigDecimal>> buildCloseSeries(
            List<LocalDate> commonDates,
            Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily) {
        Map<Integer, List<BigDecimal>> closes = new HashMap<>();
        for (LocalDate date : commonDates) {
            for (Map.Entry<Integer, StockAlphaDailyCloseCalculator.CloseResult> entry : daily.get(date).entrySet()) {
                closes.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue().closePrice());
            }
        }
        return closes;
    }

    /**
     * α日线决策结果。
     *
     * @param decisionDate          决策日期
     * @param ready                 是否满足决策前置条件
     * @param commonDayCount        共同有效日数量
     * @param rankings              排名结果
     * @param targetStocksId        目标股票ID
     * @param event                 目标事件
     * @param phase                 消费阶段
     * @param executionBarStartTime 固定执行bar起点
     */
    public record DecisionResult(
            LocalDate decisionDate,
            boolean ready,
            int commonDayCount,
            List<StockAlphaRankingResult> rankings,
            Integer targetStocksId,
            StockAlphaTargetPolicy.TargetEvent event,
            Integer phase,
            LocalDateTime executionBarStartTime) {
    }

    /**
     * 单次决策的纯计算结果。
     *
     * @param decisionDate   决策日期(最后共同有效日)
     * @param ready          是否满足决策前置条件
     * @param commonDayCount 共同有效日数量
     * @param daily          已读取的日线收盘数据
     * @param rankings       排名结果
     * @param targetStocksId 目标股票ID
     * @param event          目标事件
     */
    private record Calculation(
            LocalDate decisionDate,
            boolean ready,
            int commonDayCount,
            Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily,
            List<StockAlphaRankingResult> rankings,
            Integer targetStocksId,
            StockAlphaTargetPolicy.TargetEvent event) {
        /**
         * 返回决策日期当天的收盘结果,供排名持久化使用。
         *
         * @return 决策日期收盘结果;不存在时为空
         */
        private Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> latestCloses() {
            return daily == null ? Map.of() : daily.getOrDefault(decisionDate, Map.of());
        }
    }
}
