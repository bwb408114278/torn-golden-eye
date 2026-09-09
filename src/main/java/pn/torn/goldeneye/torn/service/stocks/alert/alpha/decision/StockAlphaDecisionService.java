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
import java.util.stream.Collectors;

/**
 * α策略日线决策服务。
 *
 * <p>本服务只生成可追溯的日线决策记录，不创建交易批次、不扣减资金且不发送通知。</p>
 *
 * <p>时间因果:本服务只接收决策时点,执行bar起点由
 * {@link StockAlphaExecutionBarPolicy#expectedExecutionBarStart(LocalDateTime)}计算后持久化,
 * 即"决策时点所在15分钟桶 + 15分钟"。执行阶段只消费持久化执行桶,不得用轮次时间反推,
 * 也不跨桶追补;已存在决策时只复用原执行桶,不重新推导。</p>
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlphaDecisionService {
    /**
     * 来源快照摘要与当前日线排名快照不一致时的不可复核标记。
     */
    public static final String SOURCE_NOT_REPRODUCIBLE = "SOURCE_NOT_REPRODUCIBLE";

    private static final String PENDING_STATUS = "PENDING";

    private final StockAlphaDailyCloseService dailyCloseService;
    private final TornStockAlphaDecisionDAO decisionDAO;

    /**
     * 按指定决策时点生成或读取唯一α决策。
     *
     * @param decisionDate      决策日期
     * @param decisionTime      决策时点;执行bar由该时点推导,不得直接传入执行bar
     * @param decisionBarPrices 决策时点各股票的已结束bar最后价,用于固化信号参考价
     * @return 已持久化决策
     */
    public DecisionResult decide(LocalDate decisionDate, LocalDateTime decisionTime,
                                 Map<Integer, BigDecimal> decisionBarPrices) {
        return decideInternal(decisionDate, null, null, decisionTime, decisionBarPrices);
    }

    /**
     * 按指定决策时点和持仓上下文生成或读取唯一α决策。
     *
     * @param decisionDate      决策日期
     * @param currentStocksId   当前持仓股票ID
     * @param currentBatchId    当前持仓批次ID
     * @param decisionTime      决策时点;执行bar由该时点推导,不得直接传入执行bar
     * @param decisionBarPrices 决策时点各股票的已结束bar最后价,用于固化信号参考价
     * @return 已持久化决策
     */
    public DecisionResult decide(LocalDate decisionDate, Integer currentStocksId, Long currentBatchId,
                                 LocalDateTime decisionTime, Map<Integer, BigDecimal> decisionBarPrices) {
        return decideInternal(decisionDate, currentStocksId, currentBatchId, decisionTime, decisionBarPrices);
    }

    /**
     * 按共同有效日、持仓上下文和决策时点生成或读取唯一决策。
     * <p>
     * 决策时点由生产编排显式传入(轮次已结束bar起点),执行bar起点由
     * {@link StockAlphaExecutionBarPolicy}按"决策桶 + 15分钟"计算后持久化,
     * 执行阶段只消费该持久化值,不得再由轮次时间反推。
     * <p>
     * 为避免普通轮次产生不必要的重负载,本方法按以下顺序收敛:
     * <ol>
     *   <li>先按共同有效日廉价统计判断是否为phase边界:非决策日直接返回未就绪,
     *       不读取完整历史窗口、不排名;</li>
     *   <li>再按"决策业务日 + phase"业务键加锁读取已持久化决策:存在即复用原执行桶,
     *       不读取完整历史窗口、不重新排名、不写排名快照;</li>
     *   <li>只有确认需要生成新决策时,才读取完整历史窗口并执行一次排名。</li>
     * </ol>
     *
     * @param decisionDate      决策日期
     * @param currentStocksId   当前持仓股票ID
     * @param currentBatchId    当前持仓批次ID
     * @param decisionTime      决策时点
     * @param decisionBarPrices 决策时点各股票的已结束bar最后价
     * @return 已持久化决策
     */
    private DecisionResult decideInternal(LocalDate decisionDate, Integer currentStocksId, Long currentBatchId,
                                          LocalDateTime decisionTime, Map<Integer, BigDecimal> decisionBarPrices) {
        Objects.requireNonNull(decisionDate, "决策日期不能为空");
        Objects.requireNonNull(decisionTime, "决策时点不能为空");
        LocalDateTime executionBar = StockAlphaExecutionBarPolicy.expectedExecutionBarStart(decisionTime);
        List<LocalDate> commonDates = dailyCloseService.commonValidDates(decisionDate);
        int commonDayCount = commonDates.size();
        if (!isDecisionDay(commonDayCount)) {
            return notReady(decisionDate, commonDayCount, executionBar);
        }
        int phase = phaseOf(commonDayCount);
        TornStockAlphaDecisionDO persisted = decisionDAO.selectByBusinessKeyForUpdate(decisionDate, phase);
        if (persisted != null) {
            log.debug("α当前phase已存在决策,复用持久化执行桶且不重新排名: decisionId={}, executionBar={}",
                    persisted.getId(), persisted.getExecutionBarStartTime());
            return toDecisionResult(persisted, List.of());
        }
        Calculation calculation = calculate(decisionDate, currentStocksId, commonDayCount);
        if (!calculation.ready()) {
            return notReady(calculation, executionBar);
        }
        DecisionResult candidate = new DecisionResult(calculation.decisionDate(), true, calculation.commonDayCount(),
                calculation.rankings(), calculation.targetStocksId(), calculation.event(), phase, executionBar);
        TornStockAlphaDecisionDO decision = toDecisionDO(candidate, phase, currentBatchId, executionBar,
                decisionBarPrices == null ? null : decisionBarPrices.get(candidate.targetStocksId()));
        dailyCloseService.persistRankings(calculation.decisionDate(), calculation.latestCloses(),
                calculation.rankings());
        if (decisionDAO.insertIgnoreConflict(decision) != 1) {
            log.warn("α决策插入未生效,本次不消费phase: decisionDate={}, phase={}",
                    calculation.decisionDate(), phase);
            return notReady(calculation, executionBar);
        }
        return toDecisionResult(decision, calculation.rankings());
    }

    /**
     * 校验决策的来源摘要与当前日线排名快照是否仍然一致。
     * <p>
     * 排名事实保存在日线快照表且按业务键UPSERT:后续补采或重建同一自然日后,
     * 回查得到的排名向量可能已变更。本方法按决策业务日重新计算排名向量与摘要并比对,
     * 不一致即判定不可复核,调用方必须fail-closed,不得把新结果冒充当时的决策事实。
     *
     * @param decision 已持久化决策
     * @return 摘要可由当前日线快照复现时返回true
     */
    public boolean isSourceReproducible(TornStockAlphaDecisionDO decision) {
        if (decision == null || decision.getSourceSnapshotDigest() == null
                || decision.getDecisionBusinessDate() == null || decision.getCommonDayIndex() == null) {
            return false;
        }
        List<StockAlphaRankingResult> rankings = rankingsOf(decision.getDecisionBusinessDate());
        if (rankings.isEmpty()) {
            return false;
        }
        return decision.getSourceSnapshotDigest().equals(
                buildSourceSnapshotDigest(decision.getDecisionBusinessDate(), decision.getCommonDayIndex(), rankings));
    }

    /**
     * 判断决策是否携带合法的决策时点参考价。
     *
     * @param decision 已持久化决策
     * @return 参考价存在且为正时返回true
     */
    public static boolean hasUsableSignalReferencePrice(TornStockAlphaDecisionDO decision) {
        BigDecimal price = decision == null ? null : decision.getSignalReferencePrice();
        return price != null && price.signum() > 0;
    }

    /**
     * 构造不消费phase且未读取历史窗口的未就绪决策结果。
     *
     * @param decisionDate   决策日期
     * @param commonDayCount 共同有效日数量
     * @param executionBar   按决策时点推导的执行bar起点
     * @return ready=false且不携带目标与phase的决策结果
     */
    private DecisionResult notReady(LocalDate decisionDate, int commonDayCount, LocalDateTime executionBar) {
        return new DecisionResult(decisionDate, false, commonDayCount, null, null,
                StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT, null, executionBar);
    }

    /**
     * 构造不消费phase的未就绪决策结果。
     *
     * @param calculation  本次计算结果
     * @param executionBar 按决策时点推导的执行bar起点
     * @return ready=false且不携带目标与phase的决策结果
     */
    private DecisionResult notReady(Calculation calculation, LocalDateTime executionBar) {
        return new DecisionResult(calculation.decisionDate(), false, calculation.commonDayCount(), null, null,
                calculation.event(), null, executionBar);
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
     * @param executionBarStartTime 按决策时点推导的执行bar起点
     * @param signalReferencePrice  决策时点参考价;缺失时执行阶段fail-closed
     * @return 决策持久化对象
     */
    private TornStockAlphaDecisionDO toDecisionDO(DecisionResult result, int phase, Long currentBatchId,
                                                  LocalDateTime executionBarStartTime,
                                                  BigDecimal signalReferencePrice) {
        TornStockAlphaDecisionDO decision = new TornStockAlphaDecisionDO();
        decision.setDecisionBusinessDate(result.decisionDate());
        decision.setCommonDayIndex(result.commonDayCount());
        decision.setPhase(phase);
        decision.setCurrentBatchId(currentBatchId);
        decision.setExecutionBarStartTime(executionBarStartTime);
        decision.setDecisionType(result.event().name());
        decision.setSourceSnapshotDigest(buildSourceSnapshotDigest(result));
        decision.setSignalReferencePrice(signalReferencePrice);
        decision.setExecutionStatus(PENDING_STATUS);
        decision.setSelectedStocksId(result.targetStocksId());
        return decision;
    }

    /**
     * 按决策结果构造来源快照摘要。
     *
     * @param result 决策结果
     * @return 摘要
     */
    private String buildSourceSnapshotDigest(DecisionResult result) {
        return buildSourceSnapshotDigest(result.decisionDate(), result.commonDayCount(), result.rankings());
    }

    /**
     * 按决策事实构造来源快照摘要。
     *
     * @param decisionDate   决策日期
     * @param commonDayCount 共同有效日序号
     * @param rankings       排名向量
     * @return 摘要
     */
    private String buildSourceSnapshotDigest(LocalDate decisionDate, int commonDayCount,
                                             List<StockAlphaRankingResult> rankings) {
        String source = decisionDate + "|" + commonDayCount + "|"
                + rankings.stream().sorted(Comparator.comparing(StockAlphaRankingResult::stocksId))
                .map(ranking -> ranking.stocksId() + ":" + ranking.r20() + ":" + ranking.r1() + ":"
                        + ranking.r20Rank() + ":" + ranking.r1Rank() + ":" + ranking.alphaScore() + ":"
                        + ranking.rankPosition()).collect(Collectors.joining("|"));
        return StockHashUtils.sha256(StockAlphaRuleDefinition.RULE_VERSION + "|" + source);
    }

    /**
     * 将持久化决策转换为领域结果。
     * <p>
     * 日期、phase、目标、事件和执行桶全部取自持久化决策,不混入本次新算结果;
     * 只有新生成决策才允许携带本次计算的排名,避免两套事实混用。
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
     * 只读取已完整持久化的最近排名窗口日线快照并执行纯内存排名,不重写历史快照。
     * 共同有效日序号由调用方按全量共同有效日统计传入,不得以排名窗口内的天数代替,
     * 否则phase会因周末和节假日漂移。
     *
     * @param decisionDate    决策日期
     * @param currentStocksId 当前持仓股票ID
     * @param commonDayCount  决策日期对应的共同有效日序号
     * @return 计算结果
     */
    private Calculation calculate(LocalDate decisionDate, Integer currentStocksId, int commonDayCount) {
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily =
                dailyCloseService.loadDailyCloses(decisionDate);
        List<LocalDate> rankingDates = completeDates(daily);
        if (rankingDates.size() < StockAlphaRuleDefinition.RANKING_MIN_COMMON_DAYS) {
            return new Calculation(decisionDate, false, commonDayCount, daily, List.of(), null,
                    StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT);
        }
        List<StockAlphaRankingResult> rankings = rank(rankingDates, daily);
        StockAlphaTargetPolicy.TargetResult target = StockAlphaTargetPolicy.decide(commonDayCount, rankings, currentStocksId);
        return new Calculation(rankingDates.getLast(), true, commonDayCount, daily, rankings,
                target.targetStocksId(), target.event());
    }

    /**
     * 按决策业务日重新计算当前日线快照对应的排名向量。
     *
     * @param decisionDate 决策业务日
     * @return 排名向量;数据不足时返回空列表
     */
    private List<StockAlphaRankingResult> rankingsOf(LocalDate decisionDate) {
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily =
                dailyCloseService.loadDailyCloses(decisionDate);
        List<LocalDate> rankingDates = completeDates(daily);
        if (rankingDates.size() < StockAlphaRuleDefinition.RANKING_MIN_COMMON_DAYS) {
            return List.of();
        }
        return rank(rankingDates, daily);
    }

    /**
     * 筛选成员完整的排名日期。
     *
     * @param daily 日线收盘数据
     * @return 升序排名日期
     */
    private List<LocalDate> completeDates(
            Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily) {
        return daily.entrySet().stream()
                .filter(entry -> entry.getValue().size() == StockAlphaRuleDefinition.MEMBER_COUNT
                        && entry.getValue().values().stream().allMatch(Objects::nonNull))
                .map(Map.Entry::getKey).sorted().toList();
    }

    /**
     * 按排名日期执行唯一排名实现。
     *
     * @param rankingDates 排名日期
     * @param daily        日线收盘数据
     * @return 排名向量
     */
    private List<StockAlphaRankingResult> rank(
            List<LocalDate> rankingDates,
            Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily) {
        return StockAlphaRankingCalculator.calculate(buildCloseSeries(rankingDates, daily));
    }

    /**
     * 判断共同有效日数量是否为决策日。
     * <p>
     * 必须显式排除未达到预热共同有效日的情形:仅用取模判断会让小于预热值的数量
     * (例如55或0)因负数或零取模为0而被误判为决策日。
     *
     * @param commonDayCount 共同有效日数量
     * @return 达到预热要求且落在决策间隔边界时返回true
     */
    private boolean isDecisionDay(int commonDayCount) {
        return commonDayCount >= StockAlphaRuleDefinition.WARMUP_COMMON_DAYS
                && (commonDayCount - StockAlphaRuleDefinition.WARMUP_COMMON_DAYS)
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
     * @param rankings              排名结果;复用已持久化决策时为空列表
     * @param targetStocksId        目标股票ID
     * @param event                 目标事件
     * @param phase                 消费阶段
     * @param executionBarStartTime 由决策时点推导并持久化的执行bar起点
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
