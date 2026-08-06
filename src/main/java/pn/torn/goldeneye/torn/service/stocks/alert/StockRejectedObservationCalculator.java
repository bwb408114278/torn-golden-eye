package pn.torn.goldeneye.torn.service.stocks.alert;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockObservationResultEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 拒绝观察理论路径计算器。
 *
 * <p>只计算纯价格路径,不修改正式组合、Shadow资金或通知状态。</p>
 *
 * <p>对可观察拒绝原因建立理论 ENTRY → 冻结退出信号 → 紧邻连续 bar 理论 EXIT 的研究结果:
 * 理论入场后逐 bar 使用与正式链相同的 {@link StockBatchExitService} 退出规则评估
 * (+0.8%目标 / -1.5%风险 / RANGE position30&gt;=0.60且盈利 / 14天);
 * 退出信号后仅接受紧邻下一连续可用 bar 成交,不跨缺口;
 * {@code laterMfe/laterMae} 始终按完整14天纯价格路径计算,不被理论退出截断;
 * 理论净收益按0.1%卖出费计算。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.29
 */
public final class StockRejectedObservationCalculator {

    /**
     * 向上价格偏离阈值。
     */
    static final BigDecimal ENTRY_DEVIATION_THRESHOLD = new BigDecimal("0.0015");
    /**
     * 观察天数。
     */
    static final int OBSERVATION_DAYS = 14;
    /**
     * 收益计算精度。
     */
    private static final int SCALE = 18;
    /**
     * 无法理论入场结果。
     */
    static final String NO_THEORETICAL_ENTRY = StockObservationResultEnum.NO_THEORETICAL_ENTRY.getCode();
    /**
     * 观察数据不足结果。
     */
    static final String OBSERVATION_DATA_INSUFFICIENT =
            StockObservationResultEnum.OBSERVATION_DATA_INSUFFICIENT.getCode();
    /**
     * 观察完成结果。
     */
    static final String OBSERVATION_COMPLETED = StockObservationResultEnum.OBSERVATION_COMPLETED.getCode();
    /**
     * 与正式链一致的退出评估器(无状态纯逻辑)。
     */
    private static final StockBatchExitService EXIT_SERVICE = new StockBatchExitService();

    private StockRejectedObservationCalculator() {
    }

    /**
     * 计算单个拒绝观察事件的理论结果(不提供特征时按无区间特征处理)。
     *
     * @param event 原始信号事件
     * @param batch 拒绝观察批次
     * @param bars  已批量加载的相关bar
     * @return 理论观察结果
     */
    public static Result calculate(TornStockSignalEventDO event,
                                   TornStockVirtualBatchDO batch,
                                   List<TornStockMarketBar15mDO> bars) {
        return calculate(event, batch, bars, List.of());
    }

    /**
     * 计算单个拒绝观察事件的理论结果。
     *
     * @param event    原始信号事件
     * @param batch    拒绝观察批次
     * @param bars     已批量加载的相关bar
     * @param features 已批量加载的对应策略特征(按bar时间索引,用于RANGE退出评估)
     * @return 理论观察结果
     */
    public static Result calculate(TornStockSignalEventDO event,
                                   TornStockVirtualBatchDO batch,
                                   List<TornStockMarketBar15mDO> bars,
                                   List<TornStockStrategyFeature15mDO> features) {
        Objects.requireNonNull(event, "信号事件不能为空");
        Objects.requireNonNull(batch, "拒绝观察批次不能为空");
        Objects.requireNonNull(bars, "行情bar不能为空");
        Objects.requireNonNull(features, "策略特征不能为空");
        LocalDateTime expectedEntryTime = batch.getExpectedEntryBarTime();
        TornStockMarketBar15mDO entryBar = findBar(bars, expectedEntryTime);
        if (!Stock15mBarBuildService.isUsable(entryBar)
                || event.getSignalReferencePrice() == null || entryBar.getLastPrice() == null) {
            return unresolved(NO_THEORETICAL_ENTRY, batch.getEntryStaleAt(), false);
        }

        BigDecimal entryPrice = entryBar.getLastPrice();
        if (isUpwardDeviationExceeded(event.getSignalReferencePrice(), entryPrice)) {
            return unresolved(NO_THEORETICAL_ENTRY, batch.getEntryStaleAt(), false);
        }

        LocalDateTime observationStart = entryBar.getBarEndTime();
        LocalDateTime deadline = entryBar.getBarStartTime().plusDays(OBSERVATION_DAYS);
        List<TornStockMarketBar15mDO> observedBars = findBarsInObservationWindow(
                bars, observationStart, deadline).stream()
                .filter(Stock15mBarBuildService::isUsable)
                .filter(bar -> bar.getLastPrice() != null)
                .toList();
        if (observedBars.isEmpty()) {
            return unresolved(OBSERVATION_DATA_INSUFFICIENT, deadline, true);
        }

        BigDecimal laterMfe = observedBars.stream()
                .map(bar -> priceReturn(entryPrice, bar.getLastPrice()))
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        BigDecimal laterMae = observedBars.stream()
                .map(bar -> priceReturn(entryPrice, bar.getLastPrice()))
                .min(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);

        TheoreticalExit theoreticalExit = simulateTheoreticalExit(batch, entryBar, observedBars,
                indexFeatures(features));
        if (theoreticalExit != null) {
            return new Result(laterMfe, laterMae, theoreticalExit.exitTime(), OBSERVATION_COMPLETED,
                    hasObservationDataGap(bars, observationStart, deadline),
                    entryBar.getBarStartTime(), entryPrice,
                    theoreticalExit.signalTime(), theoreticalExit.exitTime(),
                    theoreticalExit.exitPrice(), theoreticalExit.closeType(),
                    theoreticalExit.netReturn());
        }

        LocalDateTime finalBarTime = observedBars.getLast().getBarStartTime();
        BigDecimal finalPrice = observedBars.getLast().getLastPrice();
        return new Result(laterMfe, laterMae, deadline, OBSERVATION_COMPLETED,
                hasObservationDataGap(bars, observationStart, deadline),
                entryBar.getBarStartTime(), entryPrice,
                null, null, null, null,
                theoreticalNetReturn(entryPrice, finalPrice));
    }

    /**
     * 模拟理论正式生命周期: 逐bar评估冻结退出规则,退出信号后紧邻下一连续可用bar成交。
     *
     * @param batch        拒绝观察批次
     * @param entryBar     理论入场bar
     * @param observedBars 观察窗口内可用bar(按时间升序)
     * @param featureByBar 按bar开始时间索引的策略特征
     * @return 提前退出结果;14天窗口内无成交时返回null
     */
    private static TheoreticalExit simulateTheoreticalExit(TornStockVirtualBatchDO batch,
                                                           TornStockMarketBar15mDO entryBar,
                                                           List<TornStockMarketBar15mDO> observedBars,
                                                           Map<LocalDateTime, TornStockStrategyFeature15mDO> featureByBar) {
        BigDecimal entryPrice = entryBar.getLastPrice();
        LocalDateTime entryTime = entryBar.getBarStartTime();
        TornStockVirtualBatchDO pseudoBatch = buildPseudoBatch(batch, entryPrice, entryTime);

        LocalDateTime signalTime = null;
        StockBatchExitService.ExitEvaluation pendingExit = null;
        for (TornStockMarketBar15mDO bar : observedBars) {
            if (pendingExit != null) {
                if (isAdjacentToSignal(signalTime, bar.getBarStartTime())) {
                    return buildTheoreticalExit(pendingExit, signalTime, bar, entryPrice);
                }
                pendingExit = null;
                signalTime = null;
            }
            StockBatchExitService.ExitEvaluation evaluation = EXIT_SERVICE.evaluateExit(pseudoBatch,
                    bar.getLastPrice(),
                    featureByBar.get(bar.getBarStartTime()) == null
                            ? null : featureByBar.get(bar.getBarStartTime()).getPosition30(),
                    featureByBar.get(bar.getBarStartTime()) == null
                            ? null : featureByBar.get(bar.getBarStartTime()).getLow30d(),
                    featureByBar.get(bar.getBarStartTime()) == null
                            ? null : featureByBar.get(bar.getBarStartTime()).getHigh30d(),
                    bar.getBarStartTime());
            if (evaluation.shouldExit()) {
                pendingExit = evaluation;
                signalTime = bar.getBarStartTime();
            }
        }
        return null;
    }

    /**
     * 构建临时批次供正式退出评估器复用(仅承载入场价、入场时间与主策略)。
     *
     * @param batch       拒绝观察批次
     * @param entryPrice  理论入场价
     * @param entryTime   理论入场时间
     * @return 临时批次
     */
    private static TornStockVirtualBatchDO buildPseudoBatch(TornStockVirtualBatchDO batch,
                                                            BigDecimal entryPrice,
                                                            LocalDateTime entryTime) {
        TornStockVirtualBatchDO pseudo = new TornStockVirtualBatchDO();
        pseudo.setEntryReferencePrice(entryPrice);
        pseudo.setEntryTime(entryTime);
        pseudo.setPrimaryStrategy(batch.getPrimaryStrategy());
        pseudo.setBatchNo(batch.getBatchNo());
        return pseudo;
    }

    /**
     * 判断bar是否为退出信号bar的紧邻下一连续bar(相差一个桶)。
     *
     * @param signalTime 退出信号bar开始时间
     * @param barTime    候选成交bar开始时间
     * @return 紧邻下一bar返回true
     */
    private static boolean isAdjacentToSignal(LocalDateTime signalTime, LocalDateTime barTime) {
        if (signalTime == null) {
            return false;
        }
        return signalTime.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES).equals(barTime);
    }

    /**
     * 构建提前退出理论结果(净收益扣0.1%卖出费)。
     *
     * @param exit      退出评估结果
     * @param signalTime 退出信号bar开始时间
     * @param exitBar   理论成交bar
     * @param entryPrice 理论入场价
     * @return 提前退出理论结果
     */
    private static TheoreticalExit buildTheoreticalExit(StockBatchExitService.ExitEvaluation exit,
                                                        LocalDateTime signalTime,
                                                        TornStockMarketBar15mDO exitBar,
                                                        BigDecimal entryPrice) {
        String closeType = exit.closeType() == null ? null : exit.closeType().getCode();
        return new TheoreticalExit(signalTime, exitBar.getBarStartTime(), exitBar.getLastPrice(),
                closeType, theoreticalNetReturn(entryPrice, exitBar.getLastPrice()));
    }

    /**
     * 计算扣0.1%卖出费后的理论净收益。
     *
     * @param entryPrice 理论入场价
     * @param exitPrice  理论退出或期末价
     * @return 理论净收益(entryPrice非正时返回null)
     */
    private static BigDecimal theoreticalNetReturn(BigDecimal entryPrice, BigDecimal exitPrice) {
        if (entryPrice == null || entryPrice.signum() <= 0 || exitPrice == null) {
            return null;
        }
        return exitPrice.divide(entryPrice, SCALE, RoundingMode.HALF_UP)
                .multiply(StockBatchExitService.SELL_FEE_RATE)
                .subtract(BigDecimal.ONE);
    }

    private static boolean hasObservationDataGap(List<TornStockMarketBar15mDO> bars,
                                                 LocalDateTime observationStart,
                                                 LocalDateTime deadline) {
        Map<LocalDateTime, TornStockMarketBar15mDO> barsByStart = findBarsInObservationWindow(
                bars, observationStart, deadline).stream()
                .collect(Collectors.toMap(TornStockMarketBar15mDO::getBarStartTime,
                        Function.identity(), (left, right) -> left));
        for (LocalDateTime cursor = observationStart;
             !cursor.isAfter(deadline);
             cursor = cursor.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES)) {
            TornStockMarketBar15mDO bar = barsByStart.get(cursor);
            if (!Stock15mBarBuildService.isUsable(bar) || bar.getLastPrice() == null) {
                return true;
            }
        }
        return false;
    }

    private static TornStockMarketBar15mDO findBar(List<TornStockMarketBar15mDO> bars,
                                                   LocalDateTime expectedTime) {
        if (expectedTime == null) {
            return null;
        }
        return bars.stream()
                .filter(Objects::nonNull)
                .filter(bar -> expectedTime.equals(bar.getBarStartTime()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 筛选观察窗口内具有时间锚点的行情bar。
     *
     * @param bars             原始行情bar
     * @param observationStart 观察窗口开始时间
     * @param deadline         观察窗口截止时间
     * @return 按bar开始时间排序的窗口行情bar
     */
    private static List<TornStockMarketBar15mDO> findBarsInObservationWindow(
            List<TornStockMarketBar15mDO> bars,
            LocalDateTime observationStart,
            LocalDateTime deadline) {
        return bars.stream()
                .filter(Objects::nonNull)
                .filter(bar -> bar.getBarStartTime() != null)
                .filter(bar -> !bar.getBarStartTime().isBefore(observationStart)
                        && !bar.getBarStartTime().isAfter(deadline))
                .sorted(Comparator.comparing(TornStockMarketBar15mDO::getBarStartTime))
                .toList();
    }

    /**
     * 按bar开始时间索引策略特征(同bar多条时保留最后一条)。
     *
     * @param features 策略特征列表
     * @return bar开始时间→特征映射
     */
    private static Map<LocalDateTime, TornStockStrategyFeature15mDO> indexFeatures(
            List<TornStockStrategyFeature15mDO> features) {
        Map<LocalDateTime, TornStockStrategyFeature15mDO> byBar = new HashMap<>();
        for (TornStockStrategyFeature15mDO feature : features) {
            if (feature != null && feature.getBarStartTime() != null) {
                byBar.put(feature.getBarStartTime(), feature);
            }
        }
        return byBar;
    }

    private static boolean isUpwardDeviationExceeded(BigDecimal signalPrice, BigDecimal entryPrice) {
        return entryPrice.compareTo(signalPrice) > 0
                && entryPrice.divide(signalPrice, SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .compareTo(ENTRY_DEVIATION_THRESHOLD) > 0;
    }

    private static BigDecimal priceReturn(BigDecimal entryPrice, BigDecimal price) {
        return price.divide(entryPrice, SCALE, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
    }

    private static Result unresolved(String reason, LocalDateTime resolvedAt,
                                     boolean observationDataIncomplete) {
        return new Result(null, null, resolvedAt, reason, observationDataIncomplete,
                null, null, null, null, null, null, null);
    }

    /**
     * 提前理论退出结果。
     *
     * @param signalTime 退出信号bar开始时间
     * @param exitTime   理论成交bar开始时间
     * @param exitPrice  理论成交价
     * @param closeType  冻结退出类型编码
     * @param netReturn  扣费后理论净收益
     */
    private record TheoreticalExit(LocalDateTime signalTime, LocalDateTime exitTime,
                                   BigDecimal exitPrice, String closeType, BigDecimal netReturn) {
    }

    /**
     * 拒绝观察计算结果。
     *
     * @param laterMfe                  后续最大有利偏移
     * @param laterMae                  后续最大不利偏移
     * @param resolvedAt                结算时间
     * @param resultCode                结果编码
     * @param observationDataIncomplete 是否存在观察数据缺口
     * @param theoreticalEntryTime      理论入场时间
     * @param theoreticalEntryPrice     理论入场价格
     * @param theoreticalExitSignalTime 理论退出信号时间
     * @param theoreticalExitTime       理论退出成交时间
     * @param theoreticalExitPrice      理论退出成交价格
     * @param theoreticalCloseType      理论退出关闭类型编码
     * @param theoreticalNetReturn      理论净收益
     */
    public record Result(BigDecimal laterMfe, BigDecimal laterMae, LocalDateTime resolvedAt,
                         String resultCode, boolean observationDataIncomplete,
                         LocalDateTime theoreticalEntryTime, BigDecimal theoreticalEntryPrice,
                         LocalDateTime theoreticalExitSignalTime, LocalDateTime theoreticalExitTime,
                         BigDecimal theoreticalExitPrice, String theoreticalCloseType,
                         BigDecimal theoreticalNetReturn) {
    }
}
