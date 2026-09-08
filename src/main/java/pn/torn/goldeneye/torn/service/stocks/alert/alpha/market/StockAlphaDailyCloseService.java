package pn.torn.goldeneye.torn.service.stocks.alert.alpha.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDailySnapshotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDailySnapshotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 股票α日线收盘服务。
 * <p>
 * 本服务区分三类动作,避免历史日线写入进入轮次资金事务:
 * <ol>
 *   <li>{@link #loadDailyCloses(LocalDate)}: 正式决策读取,只读且完整性不满足时fail-closed返回空结果。</li>
 *   <li>{@link #buildDailyClosesForEndedDay(LocalDateTime)}: 自然日最后一个15分钟桶结束后的快照构建。</li>
 *   <li>{@link #buildDailyCloses(LocalDate)}: 预填与缺口修复,只对缺失或不完整日期批量写入。</li>
 * </ol>
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlphaDailyCloseService {
    /**
     * 收盘bar构建版本。
     */
    private static final String BAR_BUILD_VERSION = Stock15mBarBuildService.BUILD_VERSION;
    /**
     * 决策窗口在预热共同有效日之外额外回看的自然日数量。
     */
    private static final int HISTORY_BUFFER_DAYS = 20;
    /**
     * 单批写入条数上限,与项目既有批量UPSERT约定一致。
     */
    private static final int BATCH_SIZE = 500;
    /**
     * 自然日最后一个15分钟桶起点。
     */
    private static final LocalTime LAST_DAY_BUCKET_START = LocalTime.of(23, 45);
    /**
     * 固定股票池成员集合。
     */
    private static final Set<Integer> MEMBER_IDS = Set.copyOf(StockAlphaRuleDefinition.stockUniverse());

    /**
     * 15分钟bar数据访问对象。
     */
    private final TornStockMarketBar15mDAO barDao;
    /**
     * α日线快照数据访问对象。
     */
    private final TornStockAlphaDailySnapshotDAO snapshotDao;

    /**
     * 读取指定结束日期前的完整日线收盘数据。
     * <p>
     * 只消费已持久化的完整快照:任一日期缺失、成员集合不一致或快照字段非法时返回空结果,
     * 不触发历史重算,不产生任何写入,供轮次事务内安全调用。
     *
     * @param endDate 结束日期(闭区间上界)
     * @return 按日期和股票ID分组的收盘结果,数据不完整时为空
     */
    public Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> loadDailyCloses(
            LocalDate endDate) {
        DateRange range = DateRange.endingAt(endDate);
        List<TornStockAlphaDailySnapshotDO> storedSnapshots = selectSnapshots(range);
        if (!isRangeComplete(storedSnapshots, range)) {
            log.warn("α日线快照不完整,本轮不参与决策: endDate={}, startDate={}", range.end(), range.start());
            return Map.of();
        }
        return toCloseResults(storedSnapshots);
    }

    /**
     * 在自然日最后一个15分钟桶结束后构建当日及历史缺口的日线收盘快照。
     *
     * @param roundTime 已结束的轮次bar起点
     * @return 本次写入的快照条数
     */
    public int buildDailyClosesForEndedDay(LocalDateTime roundTime) {
        if (roundTime == null || !LAST_DAY_BUCKET_START.equals(roundTime.toLocalTime())) {
            return 0;
        }
        return buildDailyCloses(roundTime.toLocalDate());
    }

    /**
     * 构建或修复指定结束日期前的日线收盘快照。
     * <p>
     * 只对缺失或不完整的自然日扫描bar并批量UPSERT;全部日期已完整时不读取bar、不写入。
     * 部分写入不会把该日期标记为完整,下一次构建仍会重新补齐。
     *
     * @param endDate 结束日期(闭区间上界)
     * @return 本次写入的快照条数
     */
    public int buildDailyCloses(LocalDate endDate) {
        DateRange range = DateRange.endingAt(endDate);
        List<LocalDate> missingDates = missingDates(selectSnapshots(range), range);
        if (missingDates.isEmpty()) {
            return 0;
        }
        LocalDate from = missingDates.stream().min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = missingDates.stream().max(Comparator.naturalOrder()).orElseThrow();
        List<TornStockMarketBar15mDO> bars = barDao.selectByStocksAndTimeRange(
                StockAlphaRuleDefinition.stockUniverse(), from.atStartOfDay(), to.plusDays(1).atStartOfDay(),
                BAR_BUILD_VERSION);
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> computed = calculateDaily(bars);
        List<TornStockAlphaDailySnapshotDO> pending = new ArrayList<>();
        for (LocalDate date : missingDates) {
            Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> daily = computed.get(date);
            if (daily != null && isCompleteMemberSet(daily.keySet())) {
                daily.values().forEach(close -> pending.add(buildSnapshot(close)));
            } else {
                log.warn("α日线收盘计算未覆盖完整股票池,本次不写入: businessDate={}", date);
            }
        }
        int written = batchInsert(pending);
        log.info("α日线快照构建完成: endDate={}, 缺失日期数={}, 写入条数={}",
                range.end(), missingDates.size(), written);
        return written;
    }

    /**
     * 批量写入指定排名日期的排名结果。
     *
     * @param rankingDate 排名日期
     * @param closes      该日期的收盘结果
     * @param rankings    排名结果
     */
    public void persistRankings(LocalDate rankingDate,
                                Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> closes,
                                List<StockAlphaRankingResult> rankings) {
        if (rankingDate == null || closes == null || closes.isEmpty() || rankings == null || rankings.isEmpty()) {
            return;
        }
        List<TornStockAlphaDailySnapshotDO> pending = new ArrayList<>();
        for (StockAlphaRankingResult ranking : rankings) {
            StockAlphaDailyCloseCalculator.CloseResult close = closes.get(ranking.stocksId());
            if (close == null || !rankingDate.equals(close.businessDate())) {
                log.warn("α排名缺少对应收盘快照,跳过该股票排名写入: rankingDate={}, stocksId={}",
                        rankingDate, ranking.stocksId());
                continue;
            }
            pending.add(buildRankingSnapshot(close, ranking));
        }
        batchInsert(pending);
    }

    /**
     * 按日期范围查询当前版本的日线快照。
     *
     * @param range 日期范围
     * @return 日线快照
     */
    private List<TornStockAlphaDailySnapshotDO> selectSnapshots(DateRange range) {
        return snapshotDao.selectByDateRange(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION,
                StockAlphaRuleDefinition.RULE_VERSION, range.start(), range.end());
    }

    /**
     * 判断已持久化快照是否完整覆盖日期范围内的每一天和全部股票成员。
     * <p>
     * 校验范围显式包含{@code startDate}到{@code endDate}的每个自然日,而不是只对已出现的日期判断;
     * 每一天的成员集合必须与固定股票池完全相等,从而同时拒绝缺失成员、错误成员和重复成员。
     *
     * @param snapshots 已持久化的日线快照
     * @param range     日期范围
     * @return 每一天都完整且合法时返回true
     */
    private boolean isRangeComplete(List<TornStockAlphaDailySnapshotDO> snapshots, DateRange range) {
        return missingDates(snapshots, range).isEmpty();
    }

    /**
     * 计算日期范围内缺失或不完整的自然日。
     *
     * @param snapshots 已持久化的日线快照
     * @param range     日期范围
     * @return 缺失或不完整的自然日
     */
    private List<LocalDate> missingDates(List<TornStockAlphaDailySnapshotDO> snapshots, DateRange range) {
        Map<LocalDate, Set<Integer>> validMembersByDate = snapshots == null
                ? Map.of()
                : snapshots.stream()
                .filter(StockAlphaDailyCloseService::isUsableSnapshot)
                .filter(snapshot -> range.contains(snapshot.getBusinessDate()))
                .collect(Collectors.groupingBy(TornStockAlphaDailySnapshotDO::getBusinessDate,
                        Collectors.mapping(TornStockAlphaDailySnapshotDO::getStocksId, Collectors.toSet())));
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate date = range.start(); !date.isAfter(range.end()); date = date.plusDays(1)) {
            if (!isCompleteMemberSet(validMembersByDate.get(date))) {
                missing.add(date);
            }
        }
        return missing;
    }

    /**
     * 判断成员集合是否与固定股票池完全相等。
     *
     * @param stocksIds 成员集合
     * @return 完全相等时返回true
     */
    private boolean isCompleteMemberSet(Set<Integer> stocksIds) {
        return stocksIds != null && stocksIds.equals(MEMBER_IDS);
    }

    /**
     * 判断快照是否可作为共同有效日线数据。
     *
     * @param snapshot 日线快照
     * @return 版本、有效性、价格和来源bar字段均合法时返回true
     */
    private static boolean isUsableSnapshot(TornStockAlphaDailySnapshotDO snapshot) {
        return snapshot != null
                && Boolean.TRUE.equals(snapshot.getCommonValid())
                && snapshot.getStocksId() != null
                && snapshot.getBusinessDate() != null
                && snapshot.getClosePrice() != null
                && snapshot.getClosePrice().signum() > 0
                && snapshot.getSourceBarId() != null
                && snapshot.getSourceBarStartTime() != null
                && StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION.equals(snapshot.getStockUniverseVersion())
                && StockAlphaRuleDefinition.RULE_VERSION.equals(snapshot.getAlphaRuleVersion());
    }

    /**
     * 按自然日和股票ID计算最后可用bar的收盘结果。
     *
     * @param bars 15分钟bar
     * @return 按日期和股票ID分组的收盘结果
     */
    private Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> calculateDaily(
            List<TornStockMarketBar15mDO> bars) {
        Map<LocalDate, List<TornStockMarketBar15mDO>> barsByDate = bars.stream()
                .filter(bar -> bar != null && bar.getStocksId() != null && bar.getBarStartTime() != null)
                .collect(Collectors.groupingBy(bar -> bar.getBarStartTime().toLocalDate()));
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> result = new HashMap<>();
        barsByDate.forEach((date, dailyBars) -> {
            Map<Integer, List<TornStockMarketBar15mDO>> byStock = dailyBars.stream()
                    .collect(Collectors.groupingBy(TornStockMarketBar15mDO::getStocksId));
            Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> daily = new HashMap<>();
            byStock.forEach((stocksId, stockBars) -> {
                StockAlphaDailyCloseCalculator.CloseResult close =
                        StockAlphaDailyCloseCalculator.calculate(date, stockBars);
                if (close != null) {
                    daily.put(stocksId, close);
                }
            });
            result.put(date, daily);
        });
        return result;
    }

    /**
     * 将已校验完整的日线快照转换为按日期和股票ID索引的收盘结果。
     *
     * @param snapshots 日线快照
     * @return 按日期和股票ID分组的收盘结果
     */
    private Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> toCloseResults(
            List<TornStockAlphaDailySnapshotDO> snapshots) {
        return snapshots.stream()
                .filter(StockAlphaDailyCloseService::isUsableSnapshot)
                .collect(Collectors.groupingBy(TornStockAlphaDailySnapshotDO::getBusinessDate,
                        Collectors.toMap(TornStockAlphaDailySnapshotDO::getStocksId,
                                snapshot -> new StockAlphaDailyCloseCalculator.CloseResult(
                                        snapshot.getStocksId(), snapshot.getBusinessDate(), snapshot.getClosePrice(),
                                        snapshot.getSourceBarId(), snapshot.getSourceBarStartTime()))));
    }

    /**
     * 分批写入日线快照并核对生效行数。
     *
     * @param pending 待写入快照
     * @return 实际写入条数
     */
    private int batchInsert(List<TornStockAlphaDailySnapshotDO> pending) {
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (int from = 0; from < pending.size(); from += BATCH_SIZE) {
            List<TornStockAlphaDailySnapshotDO> batch =
                    pending.subList(from, Math.min(from + BATCH_SIZE, pending.size()));
            written += snapshotDao.batchInsertIgnoreConflict(batch);
        }
        if (written != pending.size()) {
            log.warn("α日线快照批量写入条数与预期不一致,缺失日期将在下次构建重新补齐: expected={}, actual={}",
                    pending.size(), written);
        }
        return written;
    }

    /**
     * 构建日线快照。
     *
     * @param close 收盘结果
     * @return 日线快照
     */
    private TornStockAlphaDailySnapshotDO buildSnapshot(StockAlphaDailyCloseCalculator.CloseResult close) {
        TornStockAlphaDailySnapshotDO snapshot = new TornStockAlphaDailySnapshotDO();
        snapshot.setStocksId(close.stocksId());
        snapshot.setBusinessDate(close.businessDate());
        snapshot.setClosePrice(close.closePrice());
        snapshot.setSourceBarId(close.sourceBarId());
        snapshot.setSourceBarStartTime(close.sourceBarStartTime());
        snapshot.setStockUniverseVersion(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION);
        snapshot.setAlphaRuleVersion(StockAlphaRuleDefinition.RULE_VERSION);
        snapshot.setCommonValid(true);
        return snapshot;
    }

    /**
     * 构建携带排名字段的日线快照。
     *
     * @param close   收盘结果
     * @param ranking 排名结果
     * @return 日线快照
     */
    private TornStockAlphaDailySnapshotDO buildRankingSnapshot(StockAlphaDailyCloseCalculator.CloseResult close,
                                                               StockAlphaRankingResult ranking) {
        TornStockAlphaDailySnapshotDO snapshot = buildSnapshot(close);
        snapshot.setR20(ranking.r20());
        snapshot.setR1(ranking.r1());
        snapshot.setR20Rank(ranking.r20Rank());
        snapshot.setR1Rank(ranking.r1Rank());
        snapshot.setR20Normalized(ranking.r20Rank());
        snapshot.setR1Normalized(ranking.r1Rank());
        snapshot.setAlphaScore(ranking.alphaScore());
        snapshot.setRankPosition(ranking.rankPosition());
        return snapshot;
    }

    /**
     * 日线决策窗口的闭区间日期范围。
     *
     * @param start 起始日期
     * @param end   结束日期
     */
    private record DateRange(LocalDate start, LocalDate end) {
        /**
         * 按结束日期构建决策窗口。
         *
         * @param endDate 结束日期
         * @return 日期范围
         */
        private static DateRange endingAt(LocalDate endDate) {
            if (endDate == null) {
                throw new IllegalArgumentException("α日线结束日期不能为空");
            }
            long windowDays = StockAlphaRuleDefinition.WARMUP_COMMON_DAYS + (long) HISTORY_BUFFER_DAYS;
            return new DateRange(endDate.minusDays(windowDays), endDate);
        }

        /**
         * 判断日期是否落在范围内。
         *
         * @param date 日期
         * @return 落在闭区间内时返回true
         */
        private boolean contains(LocalDate date) {
            return date != null && !date.isBefore(start) && !date.isAfter(end);
        }
    }
}
