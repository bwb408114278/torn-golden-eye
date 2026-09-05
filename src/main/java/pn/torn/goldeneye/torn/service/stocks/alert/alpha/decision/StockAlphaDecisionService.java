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

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private static final int INITIAL_PHASE = 0;
    private static final String NO_ACTION_STATUS = "NO_ACTION";
    private static final String PENDING_STATUS = "PENDING";

    private final StockAlphaDailyCloseService dailyCloseService;
    private final TornStockAlphaDecisionDAO decisionDAO;

    /**
     * 根据指定日期生成或读取唯一α决策。
     *
     * @param decisionDate 决策日期
     * @return 已持久化决策；数据不足时返回未就绪的内存结果且不消费phase
     */
    public DecisionResult decide(LocalDate decisionDate) {
        TornStockAlphaDecisionDO existing = decisionDAO.selectByBusinessKeyForUpdate(decisionDate, INITIAL_PHASE);
        if (existing != null) {
            return toDecisionResult(existing);
        }
        DecisionResult result = calculate(decisionDate);
        if (!result.ready()) {
            return result;
        }
        TornStockAlphaDecisionDO decision = toDecisionDO(result, INITIAL_PHASE);
        decisionDAO.insertIgnoreConflict(decision);
        TornStockAlphaDecisionDO saved = decisionDAO.selectByBusinessKeyForUpdate(decisionDate, INITIAL_PHASE);
        return saved == null ? result : toDecisionResult(saved);
    }

    private TornStockAlphaDecisionDO toDecisionDO(DecisionResult result, int phase) {
        TornStockAlphaDecisionDO decision = new TornStockAlphaDecisionDO();
        decision.setDecisionBusinessDate(result.decisionDate());
        decision.setCommonDayIndex(result.commonDayCount());
        decision.setPhase(phase);
        decision.setDecisionType(result.event().name());
        decision.setSourceSnapshotDigest(StockAlphaRuleDefinition.RULE_VERSION + ":" + result.decisionDate());
        decision.setExecutionStatus(PENDING_STATUS);
        decision.setSelectedStocksId(result.targetStocksId());
        return decision;
    }

    private DecisionResult toDecisionResult(TornStockAlphaDecisionDO decision) {
        StockAlphaTargetPolicy.TargetEvent event = StockAlphaTargetPolicy.TargetEvent.valueOf(decision.getDecisionType());
        return new DecisionResult(decision.getDecisionBusinessDate(), true,
                decision.getCommonDayIndex(), null, decision.getSelectedStocksId(), event);
    }

    private DecisionResult calculate(LocalDate decisionDate) {
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
                    StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT);
        }
        Map<Integer, List<BigDecimal>> closes = buildCloseSeries(commonDates, daily);
        List<StockAlphaRankingResult> rankings = StockAlphaRankingCalculator.calculate(closes);
        StockAlphaTargetPolicy.TargetResult target = StockAlphaTargetPolicy.decide(commonDayCount, rankings, null);
        return new DecisionResult(commonDates.getLast(), true, commonDayCount, rankings,
                target.targetStocksId(), target.event());
    }

    private boolean isDecisionDay(int commonDayCount) {
        return (commonDayCount - StockAlphaRuleDefinition.WARMUP_COMMON_DAYS)
                % StockAlphaRuleDefinition.DECISION_INTERVAL_DAYS == 0;
    }

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
     * @param decisionDate   决策日期
     * @param ready          是否满足决策前置条件
     * @param commonDayCount 共同有效日数量
     * @param rankings       排名结果
     * @param targetStocksId 目标股票ID
     * @param event          目标事件
     */
    public record DecisionResult(
            LocalDate decisionDate,
            boolean ready,
            int commonDayCount,
            List<StockAlphaRankingResult> rankings,
            Integer targetStocksId,
            StockAlphaTargetPolicy.TargetEvent event) {
    }
}
