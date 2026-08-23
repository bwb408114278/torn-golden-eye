package pn.torn.goldeneye.torn.service.stocks.alert.observation;

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
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.RangeLowerBuyStrategy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mFeatureBuildService;

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

    /**
     * 批量结算已到期的拒绝观察事件。
     * <p>
     * 批量加载对应拒绝观察批次、行情bar与策略特征后逐条调用{@link #resolveIfDue}结算,
     * 实际回写的事件在事务内一次批量更新。无批次或全部未到期时返回0。
     *
     * @param events           待结算的到期拒绝观察事件
     * @param observedAt       观察器当前时间
     * @param fallbackBarStart 无批次期望入场bar时间时兜底的行情加载起点
     * @return 本次实际结算并回写的事件数
     */
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

    /**
     * 结算单个到达入场过期点的拒绝观察事件。
     * <p>
     * 已结算或未到入场过期点的事件保持待结算;冻结原因码
     * {@link RangeLowerBuyStrategy#TREND_GUARD_DATA_INSUFFICIENT}直接写无法理论入场,
     * 其余原因复用{@link StockRejectedObservationCalculator#calculate}计算理论路径,
     * 未到观察窗口截止时保持待结算。
     *
     * @param event      信号事件
     * @param batch      拒绝观察批次
     * @param bars       该股票已批量加载的行情bar
     * @param features   该股票已批量加载的策略特征
     * @param observedAt 观察器当前时间
     * @return 已写观察结果的事件;未到期或无需回写时返回null
     */
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
        if (RangeLowerBuyStrategy.TREND_GUARD_DATA_INSUFFICIENT.equals(event.getRejectReason())) {
            return markNoTheoreticalEntry(event, entryDeadline);
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
     * 对策略趋势输入数据不足事件固定写无法理论入场。
     * <p>
     * 冻结原因码{@link RangeLowerBuyStrategy#TREND_GUARD_DATA_INSUFFICIENT}表示RANGE的趋势输入
     * (return7d/MA7/MA30)不可评估,属于数据类拒绝: 到达既有入场过期点后直接结算为
     * {@code NO_THEORETICAL_ENTRY},不得构造理论ENTRY/EXIT、MFE/MAE,即使后续存在可用bar也不改变结果。
     *
     * @param event      信号事件
     * @param resolvedAt 结算时间(入场过期点entryStaleAt)
     * @return 已写无理论入场的信号事件
     */
    private TornStockSignalEventDO markNoTheoreticalEntry(TornStockSignalEventDO event,
                                                          LocalDateTime resolvedAt) {
        event.setLaterMfe(null);
        event.setLaterMae(null);
        event.setObservationResult(StockRejectedObservationCalculator.NO_THEORETICAL_ENTRY);
        event.setObservationDataIncomplete(false);
        event.setResolvedAt(resolvedAt);
        event.setTheoreticalEntryTime(null);
        event.setTheoreticalEntryPrice(null);
        event.setTheoreticalExitSignalTime(null);
        event.setTheoreticalExitTime(null);
        event.setTheoreticalExitPrice(null);
        event.setTheoreticalCloseType(null);
        event.setTheoreticalNetReturn(null);
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
