package pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
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
@Service
@RequiredArgsConstructor
public class StockAlphaDecisionService {
    private static final String PENDING_STATUS = "PENDING";

    private final StockAlphaDailyCloseService dailyCloseService;
    private final TornStockAlphaDecisionDAO decisionDAO;

    /**
     * 根据指定日期和执行桶生成或读取唯一α决策。
     *
     * @param decisionDate          决策日期
     * @param executionBarStartTime 固定执行bar起点
     * @return 已持久化决策
     */
    public DecisionResult decide(LocalDate decisionDate, LocalDateTime executionBarStartTime) {
        return decideInternal(decisionDate, null, null, executionBarStartTime);
    }

    /**
     * 根据日期、持仓上下文和固定执行桶生成或读取唯一α决策。
     *
     * @param decisionDate          决策日期
     * @param currentStocksId       当前持仓股票ID
     * @param currentBatchId        当前持仓批次ID
     * @param executionBarStartTime 固定执行bar起点
     * @return 已持久化决策
     */
    public DecisionResult decide(LocalDate decisionDate, Integer currentStocksId, Long currentBatchId,
                                 LocalDateTime executionBarStartTime) {
        return decideInternal(decisionDate, currentStocksId, currentBatchId, executionBarStartTime);
    }

    /**
     * 按共同有效日和持仓上下文生成或读取唯一决策。
     *
     * @param decisionDate    计算截止日期
     * @param currentStocksId 当前持仓股票ID
     * @return 决策结果
     */
    private DecisionResult decideInternal(LocalDate decisionDate, Integer currentStocksId, Long currentBatchId,
                                          LocalDateTime executionBarStartTime) {
        Objects.requireNonNull(decisionDate, "决策日期不能为空");
        Objects.requireNonNull(executionBarStartTime, "执行bar不能为空");
        DecisionResult result = calculate(decisionDate, currentStocksId, executionBarStartTime);
        if (!result.ready()) {
            return result;
        }
        int phase = phaseOf(result.commonDayCount());
        TornStockAlphaDecisionDO existing = decisionDAO.selectByBusinessKeyForUpdate(result.decisionDate(), phase);
        if (existing != null) {
            return toDecisionResult(existing, result.rankings());
        }
        TornStockAlphaDecisionDO decision = toDecisionDO(result, phase, currentBatchId, executionBarStartTime);
        if (decisionDAO.insertIgnoreConflict(decision) != 1) {
            TornStockAlphaDecisionDO concurrent = decisionDAO.selectByBusinessKeyForUpdate(result.decisionDate(), phase);
            return concurrent == null ? result : toDecisionResult(concurrent, result.rankings());
        }
        return toDecisionResult(decision, result.rankings());
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
     * @param phase 消费阶段
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
     *
     * @param decision 决策持久化对象
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
     *
     * @param decisionDate 决策日期
     * @return 计算结果
     */
    private DecisionResult calculate(LocalDate decisionDate, Integer currentStocksId,
                                     LocalDateTime executionBarStartTime) {
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily =
                dailyCloseService.loadDailyCloses(decisionDate);
        List<LocalDate> commonDates = daily.entrySet().stream()
                .filter(entry -> entry.getValue().size() == StockAlphaRuleDefinition.MEMBER_COUNT
                        && entry.getValue().values().stream().allMatch(Objects::nonNull))
                .map(Map.Entry::getKey).sorted().toList();
        int commonDayCount = commonDates.size();
        if (commonDayCount < StockAlphaRuleDefinition.WARMUP_COMMON_DAYS
                || !isDecisionDay(commonDayCount)) {
            return new DecisionResult(decisionDate, false, commonDayCount, null, null,
                    StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT, null, executionBarStartTime);
        }
        Map<Integer, List<BigDecimal>> closes = buildCloseSeries(commonDates, daily);
        List<StockAlphaRankingResult> rankings = StockAlphaRankingCalculator.calculate(closes);
        dailyCloseService.persistRankings(daily, rankings);
        StockAlphaTargetPolicy.TargetResult target = StockAlphaTargetPolicy.decide(commonDayCount, rankings, currentStocksId);
        return new DecisionResult(commonDates.getLast(), true, commonDayCount, rankings,
                target.targetStocksId(), target.event(),
                phaseOf(commonDayCount), executionBarStartTime);
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
}
