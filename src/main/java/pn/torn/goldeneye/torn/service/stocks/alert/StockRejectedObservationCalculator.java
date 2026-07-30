package pn.torn.goldeneye.torn.service.stocks.alert;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockObservationResultEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 拒绝观察理论路径计算器。
 *
 * <p>只计算纯价格路径，不修改正式组合、Shadow资金或通知状态。</p>
 *
 * @author Bai
 * @version 1.2.12
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

    private StockRejectedObservationCalculator() {
    }

    /**
     * 计算单个拒绝观察事件的理论结果。
     *
     * @param event 原始信号事件
     * @param batch 拒绝观察批次
     * @param bars  已批量加载的相关bar
     * @return 理论观察结果
     */
    public static Result calculate(TornStockSignalEventDO event,
                                   TornStockVirtualBatchDO batch,
                                   List<TornStockMarketBar15mDO> bars) {
        Objects.requireNonNull(event, "信号事件不能为空");
        Objects.requireNonNull(batch, "拒绝观察批次不能为空");
        Objects.requireNonNull(bars, "行情bar不能为空");
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
        return new Result(laterMfe, laterMae, deadline, OBSERVATION_COMPLETED,
                hasObservationDataGap(bars, observationStart, deadline));
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
        return new Result(null, null, resolvedAt, reason, observationDataIncomplete);
    }

    /**
     * 拒绝观察计算结果。
     *
     * @param laterMfe                  后续最大有利偏移
     * @param laterMae                  后续最大不利偏移
     * @param resolvedAt                结算时间
     * @param resultCode                结果编码
     * @param observationDataIncomplete 是否存在观察数据缺口
     */
    public record Result(BigDecimal laterMfe, BigDecimal laterMae, LocalDateTime resolvedAt,
                         String resultCode, boolean observationDataIncomplete) {
    }
}
