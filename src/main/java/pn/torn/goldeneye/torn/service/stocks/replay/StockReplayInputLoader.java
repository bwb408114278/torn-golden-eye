package pn.torn.goldeneye.torn.service.stocks.replay;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 回放只读输入加载器。
 *
 * <p>按时间窗口分块批量只读加载 bar/feature/月度状态并索引,避免单次超大查询与N+1。
 * 加载范围: bar 覆盖回放窗口加观察尾窗(14天),feature 同bar范围,月度状态覆盖窗口内全部月份。
 * 所有读取在只读事务内执行,任何写操作都会被数据库只读事务拒绝。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@Component
public class StockReplayInputLoader {

    /**
     * 观察尾窗天数: 回放窗口结束后的前向观察/理论路径计算所需bar与feature。
     */
    static final int OBSERVATION_TAIL_DAYS = 14;
    /**
     * 单块加载天数: 避免单次查询跨度过大。
     */
    private static final int CHUNK_DAYS = 7;

    private final TornStockMarketBar15mDAO barDao;
    private final TornStockStrategyFeature15mDAO featureDao;
    private final TornStockMonthlyStateDAO monthlyStateDao;
    private final StockReplayReadOnlyGuard readOnlyGuard;

    /**
     * 构造加载器。
     *
     * @param barDao         15分钟bar只读DAO
     * @param featureDao     策略特征只读DAO
     * @param monthlyStateDao 月度状态只读DAO
     * @param readOnlyGuard  只读事务守卫
     */
    public StockReplayInputLoader(TornStockMarketBar15mDAO barDao,
                                  TornStockStrategyFeature15mDAO featureDao,
                                  TornStockMonthlyStateDAO monthlyStateDao,
                                  StockReplayReadOnlyGuard readOnlyGuard) {
        this.barDao = barDao;
        this.featureDao = featureDao;
        this.monthlyStateDao = monthlyStateDao;
        this.readOnlyGuard = readOnlyGuard;
    }

    /**
     * 加载回放输入窗口数据。
     *
     * @param request 回放请求(起止时间须已按15分钟桶对齐)
     * @return 索引后的窗口数据
     * @throws IllegalArgumentException 起止时间未对齐或开始晚于结束时抛出
     */
    public StockReplayWindowData load(StockReplayRequest request) {
        LocalDateTime start = request.startTime();
        LocalDateTime end = request.endTime();
        validateWindow(start, end);

        LocalDateTime barEnd = end.plusDays(OBSERVATION_TAIL_DAYS);
        return readOnlyGuard.inReadOnlyTransaction(status -> {
            Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> barsByStock =
                    loadBars(start, barEnd, request.barBuildVersion());
            Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> featuresByStock =
                    loadFeatures(start, barEnd, request.featureVersion());
            Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthlyStatesByMonth =
                    loadMonthlyStates(start, end);
            return new StockReplayWindowData(barsByStock, featuresByStock, monthlyStatesByMonth);
        });
    }

    /**
     * 校验回放窗口参数。
     *
     * @param start 开始时间
     * @param end   结束时间
     * @throws IllegalArgumentException 参数非法时抛出
     */
    static void validateWindow(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("回放起止时间不能为空");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("回放开始时间不能晚于结束时间");
        }
        if (!isBucketAligned(start) || !isBucketAligned(end)) {
            throw new IllegalArgumentException("回放起止时间必须按15分钟桶对齐");
        }
    }

    private static boolean isBucketAligned(LocalDateTime time) {
        return time.getMinute() % Stock15mBarBuildService.BUCKET_MINUTES == 0
                && time.getSecond() == 0 && time.getNano() == 0;
    }

    private Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> loadBars(
            LocalDateTime start, LocalDateTime end, String buildVersion) {
        Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> byStock = new HashMap<>();
        for (LocalDateTime cursor = start; cursor.isBefore(end) || cursor.isEqual(end); cursor = nextChunk(cursor)) {
            LocalDateTime chunkEnd = cursor.plusDays(CHUNK_DAYS);
            LocalDateTime capped = chunkEnd.isAfter(end) ? end : chunkEnd;
            List<TornStockMarketBar15mDO> bars = barDao.selectByTimeRange(cursor, capped, buildVersion);
            indexBars(bars, byStock);
            if (capped.isEqual(end)) {
                break;
            }
        }
        return byStock;
    }

    private void indexBars(List<TornStockMarketBar15mDO> bars,
                           Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> byStock) {
        for (TornStockMarketBar15mDO bar : bars) {
            if (bar == null || bar.getStocksId() == null || bar.getBarStartTime() == null) {
                continue;
            }
            byStock.computeIfAbsent(bar.getStocksId(), k -> new TreeMap<>())
                    .put(bar.getBarStartTime(), bar);
        }
    }

    private Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> loadFeatures(
            LocalDateTime start, LocalDateTime end, String featureVersion) {
        Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> byStock = new HashMap<>();
        for (LocalDateTime cursor = start; cursor.isBefore(end) || cursor.isEqual(end); cursor = nextChunk(cursor)) {
            LocalDateTime chunkEnd = cursor.plusDays(CHUNK_DAYS);
            LocalDateTime capped = chunkEnd.isAfter(end) ? end : chunkEnd;
            List<TornStockStrategyFeature15mDO> features =
                    featureDao.selectByTimeRange(cursor, capped, featureVersion);
            for (TornStockStrategyFeature15mDO feature : features) {
                if (feature == null || feature.getStocksId() == null || feature.getBarStartTime() == null) {
                    continue;
                }
                byStock.computeIfAbsent(feature.getStocksId(), k -> new TreeMap<>())
                        .put(feature.getBarStartTime(), feature);
            }
            if (capped.isEqual(end)) {
                break;
            }
        }
        return byStock;
    }

    private Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> loadMonthlyStates(
            LocalDateTime start, LocalDateTime end) {
        LocalDate startMonth = start.toLocalDate().withDayOfMonth(1);
        LocalDate endMonth = end.toLocalDate().withDayOfMonth(1);
        Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> byMonth = new HashMap<>();
        for (LocalDate month = startMonth; !month.isAfter(endMonth); month = month.plusMonths(1)) {
            List<TornStockMonthlyStateDO> states = monthlyStateDao.selectConfirmedByMonth(month);
            Map<Integer, TornStockMonthlyStateDO> byStock = states.stream()
                    .filter(state -> state != null && state.getStocksId() != null)
                    .collect(Collectors.toMap(TornStockMonthlyStateDO::getStocksId,
                            Function.identity(), (left, right) -> left));
            byMonth.put(month, byStock);
        }
        return byMonth;
    }

    private static LocalDateTime nextChunk(LocalDateTime cursor) {
        return cursor.plusDays(CHUNK_DAYS);
    }
}
