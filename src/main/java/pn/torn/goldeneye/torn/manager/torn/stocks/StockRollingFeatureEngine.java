package pn.torn.goldeneye.torn.manager.torn.stocks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeatureUpsert;
import pn.torn.goldeneye.torn.model.torn.stocks.trade.StockRollingState;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票回溯特征值引擎
 * <p>
 * 维护每只股票的滚动窗口状态。服务重启后首次批量调用时，
 * 自动从 DB 一次性查询所有股票最近30天历史数据预热窗口，避免冷启动导致 Z-Score 失真。
 *
 * @author Bai
 * @version 1.2.8
 * @since 2026.06.02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRollingFeatureEngine {
    private final TornStocksHistoryDAO historyDao;
    private final Map<Integer, StockRollingState> stateMap = new HashMap<>();

    public synchronized List<StockStrategyFeatureUpsert> addAndCalculate(List<StockPricePoint> points) {
        if (stateMap.isEmpty() && !CollectionUtils.isEmpty(points)) {
            warmupAll(points.getFirst().time());
        }
        return points.stream()
                .sorted(Comparator.comparing(StockPricePoint::time))
                .map(this::addSinglePoint)
                .toList();
    }

    /**
     * 重建并计算特征值（原子操作）。
     * <p>在同一个 synchronized 块内完成：reset → warmup → 逐点计算，
     * 阻塞实时轮询的 {@link #addAndCalculate}，杜绝竞态条件。
     */
    public synchronized List<StockStrategyFeatureUpsert> rebuildAndCalculate(List<StockPricePoint> points) {
        stateMap.clear();
        if (!CollectionUtils.isEmpty(points)) {
            warmupAll(points.getFirst().time());
        }
        return points.stream()
                .sorted(Comparator.comparing(StockPricePoint::time))
                .map(this::addSinglePoint)
                .toList();
    }

    private StockStrategyFeatureUpsert addSinglePoint(StockPricePoint point) {
        StockRollingState state = stateMap.computeIfAbsent(point.stocksId(), k -> new StockRollingState());
        return state.addAndCalculate(point);
    }

    /**
     * 批量预热：查询 rebuild 范围之前的 46 天历史数据，灌入窗口。
     * <p>只拉到 rebuild 起点的前一刻（pointTime 不含），与后续 addAndCalculate 处理的
     * rebuild 范围数据互不重叠，避免双写导致 MA/Std 失真。
     * <p>同时确保 priceMap 中的起点足够早，使得 rebuild 第一个点的 return14d
     * 能查到 14 天前的价格（46 天余量 > 14 天 + evict 31 天窗口）。
     */
    private void warmupAll(LocalDateTime pointTime) {
        LocalDateTime since = pointTime.minusDays(46);
        List<StockPricePoint> history = historyDao.selectHistoryPointsRange(since, pointTime);
        if (CollectionUtils.isEmpty(history)) {
            log.debug("窗口预热：无历史数据可回填");
            return;
        }
        Map<Integer, List<StockPricePoint>> grouped = new HashMap<>();
        for (StockPricePoint p : history) {
            grouped.computeIfAbsent(p.stocksId(), k -> new java.util.ArrayList<>()).add(p);
        }
        for (Map.Entry<Integer, List<StockPricePoint>> entry : grouped.entrySet()) {
            StockRollingState state = stateMap.computeIfAbsent(entry.getKey(), k -> new StockRollingState());
            for (StockPricePoint p : entry.getValue()) {
                state.warmup(p);
            }
        }
        log.info("窗口预热完成：{} 只股票，共回填 {} 条历史数据", grouped.size(), history.size());
    }
}
