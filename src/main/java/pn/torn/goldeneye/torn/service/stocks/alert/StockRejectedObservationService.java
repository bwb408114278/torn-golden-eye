package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 拒绝观察独立结算服务。
 *
 * <p>服务只处理拒绝观察研究账本,不创建正式持仓、不占用槽位、不发送通知。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRejectedObservationService {

    private final TornStockSignalEventDAO signalEventDao;
    private final TornStockVirtualBatchDAO virtualBatchDao;
    private final TornStockMarketBar15mDAO barDao;
    private final TornStockStrategyFeature15mDAO featureDao;

    /**
     * 结算指定时间范围内已经到达观察结算点的拒绝事件。
     *
     * @param startTime  事件轮次起点(含)
     * @param endTime    事件轮次终点(不含)
     * @param observedAt 观察器当前时间
     * @return 本次结算事件数
     */
    @Transactional(rollbackFor = Exception.class)
    public int resolveDueObservations(LocalDateTime startTime,
                                      LocalDateTime endTime,
                                      LocalDateTime observedAt) {
        Objects.requireNonNull(startTime, "起始时间不能为空");
        Objects.requireNonNull(endTime, "结束时间不能为空");
        Objects.requireNonNull(observedAt, "观察时间不能为空");
        if (!startTime.isBefore(endTime)) {
            return 0;
        }

        List<TornStockSignalEventDO> events = signalEventDao
                .selectPendingRejectedObservationEvents(startTime, endTime);
        return resolveEvents(events, observedAt, startTime);
    }

    /**
     * 结算全部已到期的未结算拒绝观察事件,用于启动补偿和定时任务。
     *
     * @param observedAt 观察器当前时间
     */
    @Transactional(rollbackFor = Exception.class)
    public void resolveAllDueObservations(LocalDateTime observedAt) {
        Objects.requireNonNull(observedAt, "观察时间不能为空");
        List<TornStockSignalEventDO> events = signalEventDao.selectAllPendingRejectedObservationEvents();
        resolveEvents(events, observedAt, observedAt);
    }

    private int resolveEvents(List<TornStockSignalEventDO> events, LocalDateTime observedAt,
                              LocalDateTime fallbackBarStart) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        List<Long> eventIds = events.stream().map(TornStockSignalEventDO::getId)
                .filter(Objects::nonNull).toList();
        if (eventIds.isEmpty()) {
            return 0;
        }
        Map<Long, TornStockVirtualBatchDO> batchByEventId = virtualBatchDao
                .selectRejectedObservationBatches(eventIds).stream()
                .filter(batch -> batch.getSignalEventId() != null)
                .collect(Collectors.toMap(TornStockVirtualBatchDO::getSignalEventId,
                        Function.identity(), (left, right) -> left));
        if (batchByEventId.isEmpty()) {
            return 0;
        }
        List<Integer> stocksIds = events.stream().map(TornStockSignalEventDO::getStocksId)
                .filter(Objects::nonNull).distinct().toList();
        LocalDateTime barStart = events.stream().map(event -> batchByEventId.get(event.getId()))
                .filter(Objects::nonNull).map(TornStockVirtualBatchDO::getExpectedEntryBarTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(fallbackBarStart);
        LocalDateTime windowEnd = observedAt.plusMinutes(15);
        List<TornStockMarketBar15mDO> bars = stocksIds.isEmpty() ? List.of()
                : barDao.selectByStocksAndTimeRange(stocksIds, barStart, windowEnd,
                Stock15mBarBuildService.BUILD_VERSION);
        List<TornStockStrategyFeature15mDO> features = stocksIds.isEmpty() ? List.of()
                : featureDao.selectByStocksAndTimeRange(stocksIds, barStart, windowEnd,
                Stock15mFeatureBuildService.FEATURE_VERSION);
        Map<Integer, List<TornStockMarketBar15mDO>> barsByStockId = bars.stream()
                .filter(bar -> bar.getStocksId() != null)
                .collect(Collectors.groupingBy(TornStockMarketBar15mDO::getStocksId));
        Map<Integer, List<TornStockStrategyFeature15mDO>> featuresByStockId = features.stream()
                .filter(feature -> feature.getStocksId() != null)
                .collect(Collectors.groupingBy(TornStockStrategyFeature15mDO::getStocksId));
        List<TornStockSignalEventDO> resolved = events.stream()
                .map(event -> resolveIfDue(event, batchByEventId.get(event.getId()),
                        barsByStockId.getOrDefault(event.getStocksId(), List.of()),
                        featuresByStockId.getOrDefault(event.getStocksId(), List.of()), observedAt))
                .filter(Objects::nonNull).toList();
        if (!resolved.isEmpty()) {
            signalEventDao.updateObservationResultsByIds(resolved);
        }
        return resolved.size();
    }

    private TornStockSignalEventDO resolveIfDue(TornStockSignalEventDO event,
                                                TornStockVirtualBatchDO batch,
                                                List<TornStockMarketBar15mDO> bars,
                                                List<TornStockStrategyFeature15mDO> features,
                                                LocalDateTime observedAt) {
        if (batch == null || event.getResolvedAt() != null) {
            return null;
        }
        LocalDateTime entryDeadline = batch.getEntryStaleAt();
        if (entryDeadline == null || observedAt.isBefore(entryDeadline)) {
            return null;
        }
        StockRejectedObservationCalculator.Result result =
                StockRejectedObservationCalculator.calculate(event, batch, bars, features);
        if (StockRejectedObservationCalculator.NO_THEORETICAL_ENTRY.equals(result.resultCode())) {
            event.setLaterMfe(null);
            event.setLaterMae(null);
            event.setObservationResult(result.resultCode());
            event.setObservationDataIncomplete(result.observationDataIncomplete());
            event.setResolvedAt(result.resolvedAt() == null ? entryDeadline : result.resolvedAt());
            return event;
        }
        LocalDateTime observationDeadline = batch.getExpectedEntryBarTime() == null
                ? null : batch.getExpectedEntryBarTime().plusDays(StockRejectedObservationCalculator.OBSERVATION_DAYS);
        if (result.theoreticalExitTime() == null && (observationDeadline == null
                || observedAt.isBefore(observationDeadline))) {
            return null;
        }
        applyObservationResult(event, result, observationDeadline);
        return event;
    }

    /**
     * 将理论观察结果写回信号事件(含理论退出生命周期字段)。
     *
     * @param event               信号事件
     * @param result              理论观察结果
     * @param observationDeadline 观察窗口截止时间
     */
    private void applyObservationResult(TornStockSignalEventDO event,
                                        StockRejectedObservationCalculator.Result result,
                                        LocalDateTime observationDeadline) {
        event.setLaterMfe(result.laterMfe());
        event.setLaterMae(result.laterMae());
        event.setObservationResult(result.resultCode());
        event.setObservationDataIncomplete(result.observationDataIncomplete());
        event.setResolvedAt(result.resolvedAt() == null ? observationDeadline : result.resolvedAt());
        event.setTheoreticalEntryTime(result.theoreticalEntryTime());
        event.setTheoreticalEntryPrice(result.theoreticalEntryPrice());
        event.setTheoreticalExitSignalTime(result.theoreticalExitSignalTime());
        event.setTheoreticalExitTime(result.theoreticalExitTime());
        event.setTheoreticalExitPrice(result.theoreticalExitPrice());
        event.setTheoreticalCloseType(result.theoreticalCloseType());
        event.setTheoreticalNetReturn(result.theoreticalNetReturn());
    }
}
