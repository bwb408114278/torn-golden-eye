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
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;

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
 *   <li>{@link #loadDailyCloses(LocalDate)}: 正式决策读取,只读,只消费成员完整的共同有效自然日,
 *       无行情自然日不参与统计,共同有效日不足时fail-closed返回空结果。</li>
 *   <li>{@link #buildDailyClosesForEndedDay(LocalDateTime)}: 单个已结束自然日的快照构建,
 *       首次触发点为自然日最后一个15分钟桶(23:45),未完整时后续轮次继续重试。</li>
 *   <li>{@link #buildDailyCloses(LocalDate)}: 预填与缺口修复,只对已结束自然日批量写入,
 *       不创建无交易日伪快照,也不把未结束自然日的部分bar冻结为日终事实。</li>
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
     * 排名窗口回看的自然日天数。
     * <p>
     * 排名只需要最近21个共同有效日(20日收益需要前20个交易日加当前日),但自然日窗口必须足够大
     * 才能在周末和节假日之外仍覆盖这21个交易日。
     */
    private static final int RANKING_WINDOW_DAYS = 60;
    /**
     * 预填窗口回看的自然日天数。
     * <p>
     * 预填必须让窗口内至少可能容纳预热所需的60个共同有效日,因此自然日窗口必须明显大于60
     * 才能覆盖周末与节假日。
     */
    private static final int PREFILL_WINDOW_DAYS = 100;
    /**
     * 共同有效日统计的起始日期,与上线前数据就绪门禁保持一致。
     */
    private static final LocalDate COMMON_DATE_EPOCH = LocalDate.of(2000, 1, 1);
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
     * 股票市场时间组件。
     */
    private final StockMarketClock marketClock;

    /**
     * 读取指定结束日期前的完整日线收盘数据。
     * <p>
     * 共同有效自然日的定义是"固定35支股票在同一个实际有行情的自然日均有合法收盘",
     * 不是"每个日历自然日都必须有收盘快照":周末、节假日或无有效bar的自然日没有行情,
     * 不计入共同有效日,也不要求写入快照;某个实际有交易数据的自然日缺少任一成员时,
     * 该日整体不计入共同有效日。
     * <p>
     * 只消费已持久化的完整快照:任一成员非法、价格非正、版本不符或{@code common_valid=false}
     * 时该日整体fail-closed。不触发历史重算,不产生任何写入,供轮次事务内安全调用。
     *
     * @param endDate 结束日期(闭区间上界)
     * @return 按自然日升序、只包含共同有效日的收盘结果;共同有效日不足时由决策服务fail-closed
     */
    public Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> loadDailyCloses(
            LocalDate endDate) {
        DateRange range = DateRange.endingAt(endDate, RANKING_WINDOW_DAYS);
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> common =
                commonCloses(selectSnapshots(range), range);
        if (common.isEmpty()) {
            log.warn("α日线快照无共同有效日,本轮不参与决策: endDate={}, startDate={}", range.end(), range.start());
        }
        return common;
    }

    /**
     * 查询指定结束日期之前的全部共同有效自然日。
     * <p>
     * 统计范围是自固定起始日以来的全部共同有效日,不是滑动自然日窗口内的天数:
     * 第60、65、70个共同有效日的phase规则以共同有效日序号为准,滑动窗口内计数会随
     * 周末和节假日漂移,导致phase永远无法对齐。
     * <p>
     * 只按已持久化的完整快照统计,不读取bar、不写入,供决策服务在phase边界之外
     * 廉价判断本轮是否需要执行完整α排名,避免普通轮次重复读取历史窗口。
     *
     * @param endDate 结束日期(闭区间上界)
     * @return 升序共同有效自然日
     */
    public List<LocalDate> commonValidDates(LocalDate endDate) {
        List<LocalDate> dates = snapshotDao.selectCommonValidDates(
                StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION, StockAlphaRuleDefinition.RULE_VERSION,
                StockAlphaRuleDefinition.stockUniverse(), COMMON_DATE_EPOCH, endDate);
        return dates == null ? List.of() : dates;
    }

    /**
     * 在自然日结束后构建该自然日的日线收盘快照。
     * <p>
     * 生产触发语义固定如下,不得再收窄为单一桶:
     * <ol>
     *   <li>自然日最后一个15分钟桶(23:45)所在轮次是该自然日的第一次构建触发点;</li>
     *   <li>该自然日快照仍未完整时,后续每个已结束轮次继续以同一"最近已结束自然日"为界重试,
     *       直到补齐或自然日推进;</li>
     *   <li>已结束自然日快照完整时,不读取bar、不写入;</li>
     *   <li>只有已结束自然日参与构建,绝不构建尚未结束的当前自然日。</li>
     * </ol>
     * 构建范围固定为单个已结束自然日:不把决策窗口内的每个自然日都当作必须补齐的缺口,
     * 周末、节假日等无行情自然日不进入构建范围,也不产生伪快照;
     * 长期无法自然补齐的历史已结束自然日仍由超管预填入口修复;构建失败不进入轮次资金事务,
     * 缺失日期不会被判定为完整,下一次触发仍会重新补齐。
     *
     * @param roundTime 已结束的轮次bar起点
     * @return 本次写入的快照条数
     */
    public int buildDailyClosesForEndedDay(LocalDateTime roundTime) {
        LocalDate endedDay = lastEndedNaturalDay(roundTime);
        if (endedDay == null) {
            return 0;
        }
        DateRange range = DateRange.singleDay(endedDay);
        if (isDayComplete(range)) {
            return 0;
        }
        return buildDailyCloses(range);
    }

    /**
     * 判断轮次是否为自然日最后一个15分钟桶。
     *
     * @param roundTime 已结束的轮次bar起点
     * @return 是自然日最后一个15分钟桶时返回true
     */
    private boolean isLastBucketOfNaturalDay(LocalDateTime roundTime) {
        return LAST_DAY_BUCKET_START.equals(roundTime.toLocalTime());
    }

    /**
     * 返回轮次对应的最近已结束自然日。
     * <p>
     * 23:45桶是自然日的最后一个15分钟桶,其所属自然日即为已结束自然日;
     * 其余桶所在自然日尚未结束,最近已结束自然日为其前一自然日。
     *
     * @param roundTime 已结束的轮次bar起点
     * @return 最近已结束自然日;轮次为空时返回null
     */
    private LocalDate lastEndedNaturalDay(LocalDateTime roundTime) {
        if (roundTime == null) {
            return null;
        }
        return isLastBucketOfNaturalDay(roundTime)
                ? roundTime.toLocalDate()
                : roundTime.toLocalDate().minusDays(1);
    }

    /**
     * 判断日期范围内是否存在已完整的共同有效自然日。
     *
     * @param range 日期范围
     * @return 范围内存在成员完整的自然日时返回true
     */
    private boolean isDayComplete(DateRange range) {
        return !commonCloses(selectSnapshots(range), range).isEmpty();
    }

    /**
     * 构建或修复指定结束日期前的日线收盘快照。
     * <p>
     * 结束日期必须是已结束自然日:未结束自然日的bar仍会继续增加,中途写入会把当时价格
     * 冻结为日终收盘,导致共同有效日和phase提前推进。 Bot入口已先行拒绝,
     * 本方法作为服务层第二道门禁,任何调用方都不得绕过。
     * 只对实际存在合法bar且不完整的自然日批量UPSERT;无bar的自然日不是交易日,不写入伪快照;
     * 成员不完整的自然日不写入;已完整的自然日不重复写入。
     *
     * @param endDate 结束日期(闭区间上界,必须为已结束自然日)
     * @return 本次写入的快照条数
     * @throws IllegalArgumentException 结束日期晚于最近已结束自然日时抛出
     */
    public int buildDailyCloses(LocalDate endDate) {
        LocalDate lastEndedDay = marketClock.lastEndedNaturalDay();
        if (endDate == null || endDate.isAfter(lastEndedDay)) {
            throw new IllegalArgumentException("α日线构建结束日期必须为已结束自然日: endDate=" + endDate
                    + ", lastEndedNaturalDay=" + lastEndedDay);
        }
        return buildDailyCloses(DateRange.endingAt(endDate, PREFILL_WINDOW_DAYS));
    }

    /**
     * 按指定日期范围构建日线收盘快照。
     * <p>
     * 必须先扫描bar才能识别哪些自然日真实存在行情,因此本方法始终读取范围内bar;
     * 写入范围严格限制为"有bar且35支成员全部合法且尚未完整"的自然日。
     *
     * @param range 日期范围
     * @return 本次写入的快照条数
     */
    private int buildDailyCloses(DateRange range) {
        Set<LocalDate> completeDates = commonCloses(selectSnapshots(range), range).keySet();
        List<TornStockMarketBar15mDO> bars = barDao.selectByStocksAndTimeRange(
                StockAlphaRuleDefinition.stockUniverse(), range.start().atStartOfDay(),
                range.end().plusDays(1).atStartOfDay(), BAR_BUILD_VERSION);
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> computed =
                new TreeMap<>(calculateDaily(bars));
        List<TornStockAlphaDailySnapshotDO> pending = new ArrayList<>();
        int tradableDates = 0;
        for (Map.Entry<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> entry
                : computed.entrySet()) {
            LocalDate date = entry.getKey();
            if (!range.contains(date) || completeDates.contains(date)) {
                continue;
            }
            tradableDates++;
            Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> daily = entry.getValue();
            if (isCompleteMemberSet(daily.keySet())) {
                daily.values().forEach(close -> pending.add(buildSnapshot(close)));
            } else {
                log.warn("α日线收盘计算未覆盖完整股票池,本次不写入: businessDate={}", date);
            }
        }
        int written = batchInsert(pending);
        log.info("α日线快照构建完成: endDate={}, 有行情未完成日期数={}, 写入条数={}",
                range.end(), tradableDates, written);
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
     * 将已持久化快照转换为只包含共同有效自然日的收盘结果。
     * <p>
     * 只保留"成员集合与固定股票池完全相等"的自然日:缺失成员、错误成员、非法价格、
     * 错误版本或{@code common_valid=false}都会使该日整体被排除(fail-closed)。
     * 无行情自然日没有快照,自然不会出现在结果中,不会被当作缺失。
     *
     * @param snapshots 已持久化的日线快照
     * @param range     日期范围
     * @return 按自然日升序的共同有效日收盘结果
     */
    private Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> commonCloses(
            List<TornStockAlphaDailySnapshotDO> snapshots, DateRange range) {
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> common = new TreeMap<>();
        if (snapshots == null) {
            return common;
        }
        Map<LocalDate, List<TornStockAlphaDailySnapshotDO>> byDate = snapshots.stream()
                .filter(StockAlphaDailyCloseService::isUsableSnapshot)
                .filter(snapshot -> range.contains(snapshot.getBusinessDate()))
                .collect(Collectors.groupingBy(TornStockAlphaDailySnapshotDO::getBusinessDate));
        byDate.forEach((date, daily) -> {
            Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> closes = new HashMap<>();
            daily.forEach(snapshot -> closes.put(snapshot.getStocksId(), toCloseResult(snapshot)));
            if (isCompleteMemberSet(closes.keySet())) {
                common.put(date, closes);
            }
        });
        return common;
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
     * 将单条已校验完整的日线快照转换为收盘结果。
     *
     * @param snapshot 日线快照
     * @return 收盘结果
     */
    private StockAlphaDailyCloseCalculator.CloseResult toCloseResult(
            TornStockAlphaDailySnapshotDO snapshot) {
        return new StockAlphaDailyCloseCalculator.CloseResult(
                snapshot.getStocksId(), snapshot.getBusinessDate(), snapshot.getClosePrice(),
                snapshot.getSourceBarId(), snapshot.getSourceBarStartTime());
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
         * 按结束日期和回看自然日天数构建决策窗口。
         *
         * @param endDate    结束日期
         * @param windowDays 回看自然日天数
         * @return 日期范围
         */
        private static DateRange endingAt(LocalDate endDate, int windowDays) {
            if (endDate == null) {
                throw new IllegalArgumentException("α日线结束日期不能为空");
            }
            return new DateRange(endDate.minusDays(windowDays), endDate);
        }

        /**
         * 构建只包含指定自然日的闭区间日期范围。
         *
         * @param date 自然日
         * @return 日期范围
         */
        private static DateRange singleDay(LocalDate date) {
            if (date == null) {
                throw new IllegalArgumentException("α日线自然日不能为空");
            }
            return new DateRange(date, date);
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
