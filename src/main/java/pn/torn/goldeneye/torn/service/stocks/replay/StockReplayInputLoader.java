package pn.torn.goldeneye.torn.service.stocks.replay;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayRequest;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplaySourceManifest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 回放只读输入加载器。
 *
 * <p>在单一 {@code READ ONLY + REPEATABLE READ} 事务内按时间窗口分块批量只读加载
 * bar/feature/月度状态并索引,避免单次超大查询与N+1;整个输入清单来自同一一致性快照。
 * 加载范围: bar 覆盖回放窗口加观察尾窗(14天),feature 同bar范围,月度状态通过范围批量
 * 查询一次加载窗口内全部月份。每次加载固化 {@link StockReplaySourceManifest},记录版本、
 * 行数与每股时间边界及其SHA-256摘要。所有读取在只读事务内执行,任何写操作都会被数据库
 * 只读事务拒绝。</p>
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
     * @param barDao          15分钟bar只读DAO
     * @param featureDao      策略特征只读DAO
     * @param monthlyStateDao 月度状态只读DAO
     * @param readOnlyGuard   只读事务守卫
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
     * 在单一只读 + Repeatable Read 事务内加载回放输入窗口数据并固化来源清单。
     *
     * @param request 回放请求(起止时间须已按15分钟桶对齐)
     * @return 索引后的窗口数据(含同一快照下的来源清单)
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
            StockReplaySourceManifest sourceManifest = buildSourceManifest(
                    request, barsByStock, featuresByStock, monthlyStatesByMonth);
            return new StockReplayWindowData(barsByStock, featuresByStock, monthlyStatesByMonth,
                    sourceManifest);
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

    /**
     * 判断时间点是否已按15分钟桶对齐。
     *
     * @param time 待校验的时间点
     * @return 分钟为桶倍数、秒与纳秒均为0时返回{@code true}
     */
    private static boolean isBucketAligned(LocalDateTime time) {
        return time.getMinute() % Stock15mBarBuildService.BUCKET_MINUTES == 0
                && time.getSecond() == 0 && time.getNano() == 0;
    }

    /**
     * 按时间窗口分块批量只读加载bar并索引。
     *
     * <p>以{@link #CHUNK_DAYS}天为块滚动查询,避免单次查询跨度过大;同一块内可能查询到
     * 结束时间之后的记录,由回放窗口外的数据自行按需消费。</p>
     *
     * @param start        查询开始时间(含)
     * @param end          查询结束时间(含)
     * @param buildVersion bar构建版本
     * @return 股票ID → (bar开始时间 → bar),按bar开始时间升序
     */
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

    /**
     * 将一批bar并入按股票索引的导航表。
     *
     * <p>忽略股票ID或bar开始时间为空的脏行;同一股票同一开始时间后写入者覆盖前者。</p>
     *
     * @param bars    本块查询到的bar列表
     * @param byStock 目标索引: 股票ID → (bar开始时间 → bar)
     */
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

    /**
     * 按时间窗口分块批量只读加载策略特征并索引。
     *
     * <p>与{@link #loadBars}同窗口滚动加载,忽略股票ID或bar开始时间为空的脏行。</p>
     *
     * @param start          查询开始时间(含)
     * @param end            查询结束时间(含)
     * @param featureVersion 特征计算版本
     * @return 股票ID → (bar开始时间 → 特征),按bar开始时间升序
     */
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

    /**
     * 范围批量加载窗口内全部月份的已确认月度状态(禁止按月循环发SQL)。
     *
     * @param start 回放窗口开始时间
     * @param end   回放窗口结束时间
     * @return 生效月份 → (股票ID → 已确认月度状态)
     */
    private Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> loadMonthlyStates(
            LocalDateTime start, LocalDateTime end) {
        LocalDate startMonth = start.toLocalDate().withDayOfMonth(1);
        LocalDate endMonth = end.toLocalDate().withDayOfMonth(1);
        List<TornStockMonthlyStateDO> states = monthlyStateDao.selectConfirmedByMonthRange(startMonth, endMonth);
        Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> byMonth = new LinkedHashMap<>();
        for (TornStockMonthlyStateDO state : states) {
            if (state == null || state.getStocksId() == null || state.getEffectiveMonth() == null) {
                continue;
            }
            byMonth.computeIfAbsent(state.getEffectiveMonth(), k -> new HashMap<>())
                    .put(state.getStocksId(), state);
        }
        return byMonth;
    }

    /**
     * 由已加载输入构建来源清单(行数、每股时间边界、版本与SHA-256摘要)。
     *
     * @param request              回放请求
     * @param barsByStock          加载的bar索引
     * @param featuresByStock      加载的特征索引
     * @param monthlyStatesByMonth 加载的月度状态索引
     * @return 来源清单
     */
    private StockReplaySourceManifest buildSourceManifest(
            StockReplayRequest request,
            Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> barsByStock,
            Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> featuresByStock,
            Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthlyStatesByMonth) {
        long barCount = 0;
        List<StockReplaySourceManifest.StockBoundary> boundaries = new ArrayList<>();
        Set<Integer> stocks = new TreeSet<>();
        stocks.addAll(barsByStock.keySet());
        stocks.addAll(featuresByStock.keySet());
        for (Integer stock : stocks) {
            NavigableMap<LocalDateTime, TornStockMarketBar15mDO> bars = barsByStock.get(stock);
            NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO> features = featuresByStock.get(stock);
            barCount += bars == null ? 0 : bars.size();
            boundaries.add(new StockReplaySourceManifest.StockBoundary(
                    stock,
                    firstKeyOrNull(bars),
                    lastKeyOrNull(bars),
                    firstKeyOrNull(features),
                    lastKeyOrNull(features)));
        }
        long featureCount = featuresByStock.values().stream()
                .mapToLong(m -> m == null ? 0 : m.size()).sum();
        long monthlyStateCount = monthlyStatesByMonth.values().stream()
                .mapToLong(m -> m == null ? 0 : m.size()).sum();

        Set<String> monthlyRuleVersions = new TreeSet<>();
        for (Map<Integer, TornStockMonthlyStateDO> byStock : monthlyStatesByMonth.values()) {
            for (TornStockMonthlyStateDO state : byStock.values()) {
                monthlyRuleVersions.add(state.getPersonalityRuleVersion() + "|"
                        + state.getRiskRuleVersion());
            }
        }

        LocalDate monthlyStartMonth = monthlyStatesByMonth.keySet().stream()
                .min(Comparator.naturalOrder()).orElse(null);
        LocalDate monthlyEndMonth = monthlyStatesByMonth.keySet().stream()
                .max(Comparator.naturalOrder()).orElse(null);
        LocalDate requestStartMonth = request.startTime().toLocalDate().withDayOfMonth(1);
        LocalDate requestEndMonth = request.endTime().toLocalDate().withDayOfMonth(1);

        StockReplaySourceManifest.WindowRange windowRange =
                new StockReplaySourceManifest.WindowRange(
                        request.startTime(), request.endTime(),
                        request.startTime(), request.endTime().plusDays(OBSERVATION_TAIL_DAYS),
                        request.startTime(), request.endTime().plusDays(OBSERVATION_TAIL_DAYS),
                        monthlyStartMonth == null ? requestStartMonth : monthlyStartMonth,
                        monthlyEndMonth == null ? requestEndMonth : monthlyEndMonth);
        StockReplaySourceManifest.Versions versions = new StockReplaySourceManifest.Versions(
                request.barBuildVersion(), request.featureVersion(),
                List.copyOf(monthlyRuleVersions));
        String contentSha256 = StockReplayInputDigest.compute(
                barsByStock, featuresByStock, monthlyStatesByMonth);
        return StockReplaySourceManifest.of(windowRange, versions, barCount, featureCount,
                monthlyStateCount, boundaries, contentSha256);
    }

    /**
     * 取映射中最早时间键。
     *
     * @param map 按时间排序的映射
     * @return 首个时间键;映射为空或{@code null}时返回{@code null}
     */
    private static LocalDateTime firstKeyOrNull(NavigableMap<LocalDateTime, ?> map) {
        return map == null || map.isEmpty() ? null : map.firstKey();
    }

    /**
     * 取映射中最晚时间键。
     *
     * @param map 按时间排序的映射
     * @return 末个时间键;映射为空或{@code null}时返回{@code null}
     */
    private static LocalDateTime lastKeyOrNull(NavigableMap<LocalDateTime, ?> map) {
        return map == null || map.isEmpty() ? null : map.lastKey();
    }

    /**
     * 计算下一个分块查询起点。
     *
     * @param cursor 当前分块起点
     * @return 向后推进{@link #CHUNK_DAYS}天后的时间点
     */
    private static LocalDateTime nextChunk(LocalDateTime cursor) {
        return cursor.plusDays(CHUNK_DAYS);
    }
}
