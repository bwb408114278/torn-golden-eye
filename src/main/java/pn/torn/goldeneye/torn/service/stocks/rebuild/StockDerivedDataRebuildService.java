package pn.torn.goldeneye.torn.service.stocks.rebuild;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mFeatureBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 全范围 VIP 股票派生数据重建服务。
 * <p>
 * 阶段一新增批处理门面：按自然日分片从 {@code torn_stocks_history} 读取全市场真实分钟事实，
 * 批量 UPSERT 15m bar；再按股票顺序扫描当前版本 bar，使用 {@link Stock15mFeatureCalculator}
 * 顺序计算并批量 UPSERT feature；最后把实际存在分钟事实的桶轮次标为
 * {@code REPAIRED_DATA_ONLY}（保留 COMPLETED/FAILED_FINAL），并调用月度范围重建服务。
 * <p>
 * 本服务不修改 {@code torn_stocks_history}，不调用 {@code StockRoundTransactionService}，
 * 不创建/修改 signal_event、virtual_batch、batch_mark、notice_audit、槽位、资金、冷却或复位状态。
 * 失败时返回带失败分片的 {@link StockDerivedDataRebuildResult}，已完成部分保留，可同范围幂等重跑。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDerivedDataRebuildService {

    /**
     * 批量 UPSERT 单批最大条数，避免单条 SQL 过大。
     */
    private static final int BATCH_SIZE = 500;
    /**
     * feature 后向预热窗口天数：最大 30 天。
     */
    private static final int FEATURE_WARMUP_DAYS = 30;

    private final TornStocksDAO stocksDao;
    private final TornStocksHistoryDAO stocksHistoryDao;
    private final TornStockMarketBar15mDAO bar15mDao;
    private final TornStockStrategyFeature15mDAO feature15mDao;
    private final TornStockMarketRoundDAO roundDao;
    private final StockMarketRoundFactory roundFactory;
    private final StockMarketClock marketClock;
    private final StockMonthlyStateRangeRebuildService monthlyStateRangeRebuildService;

    /**
     * 执行全范围派生数据重建。
     *
     * @param startInclusive 起始时间（含，会自动对齐到 15 分钟桶）
     * @param endExclusive   结束时间（不含，会自动对齐到 15 分钟桶）
     * @return 重建汇总；失败时包含失败分片与错误摘要
     */
    public StockDerivedDataRebuildResult rebuildRange(LocalDateTime startInclusive, LocalDateTime endExclusive) {
        long startNanos = System.nanoTime();
        LocalDateTime start = Stock15mBarBuildService.alignToBucket(startInclusive);
        LocalDateTime end = Stock15mBarBuildService.alignToBucket(endExclusive);
        if (!start.isBefore(end)) {
            return failure(start, end, start, end, "重建范围无效: start>=end");
        }
        List<TornStocksDO> stocks = stocksDao.list();
        if (CollectionUtils.isEmpty(stocks)) {
            return failure(start, end, start, end, "当前有效股票清单为空");
        }

        Set<LocalDateTime> actualBuckets = new TreeSet<>();
        Map<LocalDateTime, Integer> barCountByBucket = new HashMap<>();
        Map<LocalDateTime, Integer> featureCountByBucket = new HashMap<>();
        RebuildProgress progress = new RebuildProgress(stocks.size(), 0, 0, 0);

        try {
            // 4.2 bar 批处理：按自然日分片
            RebuildStepOutcome barOutcome = rebuildAllDayBars(start, end, actualBuckets, barCountByBucket);
            if (!barOutcome.success()) {
                return failureWithProgress(start, end,
                        progress.withProcessed(actualBuckets.size()),
                        barOutcome.failedStart(), barOutcome.failedEnd(), barOutcome.error(), startNanos);
            }
            progress = progress.withBarWrites(barOutcome.writes());

            // 4.3 feature 顺序批处理：每支股票扫描一次
            RebuildStepOutcome featureOutcome = rebuildAllStockFeatures(stocks, start, end, featureCountByBucket);
            if (!featureOutcome.success()) {
                return failureWithProgress(start, end,
                        progress.withProcessed(actualBuckets.size()),
                        featureOutcome.failedStart(), featureOutcome.failedEnd(), featureOutcome.error(), startNanos);
            }
            progress = progress.withFeatureWrites(featureOutcome.writes());

            // 4.5 数据修复 round：仅实际存在分钟事实的桶
            int dataOnlyRounds = markRoundsDataRepaired(actualBuckets, barCountByBucket, featureCountByBucket);

            // 5.3 月度状态范围重算
            monthlyStateRangeRebuildService.rebuild(start, end);

            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            long skippedEmpty = bucketCount(start, end) - actualBuckets.size();
            return new StockDerivedDataRebuildResult(
                    start, end, progress.stockCount(), actualBuckets.size(), progress.barWrites(),
                    progress.featureWrites(), dataOnlyRounds, (int) Math.max(0L, skippedEmpty),
                    elapsedMillis, null, null, null);
        } catch (Exception e) {
            log.error("派生重建-未预期异常, start={}, end={}: {}", start, end, e.getMessage(), e);
            return failureWithProgress(start, end, progress.withProcessed(actualBuckets.size()),
                    start, end, e.getMessage(), startNanos);
        }
    }

    /**
     * 按自然日分片重建全部 bar，单日失败时返回带失败分片的结果。
     *
     * @param start            起始桶（含）
     * @param end              结束桶（不含）
     * @param actualBuckets    实际出现分钟事实的桶集合（累积）
     * @param barCountByBucket 每桶 bar 数映射（累积）
     * @return bar 分片结果；成功时包含 bar 写入总数，失败时包含失败分片
     */
    private RebuildStepOutcome rebuildAllDayBars(LocalDateTime start, LocalDateTime end,
                                                 Set<LocalDateTime> actualBuckets,
                                                 Map<LocalDateTime, Integer> barCountByBucket) {
        int barWrites = 0;
        LocalDateTime dayStart = start;
        while (dayStart.isBefore(end)) {
            LocalDateTime dayEnd = dayStart.plusDays(1);
            if (dayEnd.isAfter(end)) {
                dayEnd = end;
            }
            try {
                DayBarResult dayResult = rebuildDayBars(dayStart, dayEnd, actualBuckets, barCountByBucket);
                barWrites += dayResult.barWrites();
            } catch (Exception e) {
                log.error("派生重建-bar日分片失败, dayStart={}, dayEnd={}: {}", dayStart, dayEnd, e.getMessage(), e);
                return RebuildStepOutcome.failure(dayStart, dayEnd, e.getMessage());
            }
            dayStart = dayEnd;
        }
        return RebuildStepOutcome.success(barWrites);
    }

    /**
     * 按股票顺序重建全部股票 feature，单股票失败时返回带失败分片的结果。
     *
     * @param stocks               有效股票清单
     * @param start                起始桶（含）
     * @param end                  结束桶（不含）
     * @param featureCountByBucket 每桶 feature 数映射（累积）
     * @return feature 分片结果；成功时包含 feature 写入总数，失败时包含失败范围与原因
     */
    private RebuildStepOutcome rebuildAllStockFeatures(List<TornStocksDO> stocks, LocalDateTime start,
                                                       LocalDateTime end,
                                                       Map<LocalDateTime, Integer> featureCountByBucket) {
        int featureWrites = 0;
        for (TornStocksDO stock : stocks) {
            try {
                featureWrites += rebuildStockFeatures(stock, start, end, featureCountByBucket);
            } catch (Exception e) {
                log.error("派生重建-feature股票处理失败, stockId={}, stockName={}: {}",
                        stock.getId(), stock.getStocksShortname(), e.getMessage(), e);
                return RebuildStepOutcome.failure(start, end,
                        "股票[" + stock.getStocksShortname() + "]特征处理失败: " + e.getMessage());
            }
        }
        return RebuildStepOutcome.success(featureWrites);
    }

    /**
     * 重建单个自然日内全部 bar。
     *
     * @param dayStart         自然日开始（含）
     * @param dayEnd           自然日结束（不含）
     * @param actualBuckets    实际出现分钟事实的桶集合（累积）
     * @param barCountByBucket 每桶 bar 数映射（累积）
     * @return 该日 bar 写入数
     */
    private DayBarResult rebuildDayBars(LocalDateTime dayStart, LocalDateTime dayEnd,
                                        Set<LocalDateTime> actualBuckets,
                                        Map<LocalDateTime, Integer> barCountByBucket) {
        List<StockPricePoint> points = stocksHistoryDao.selectHistoryPointsRange(dayStart, dayEnd);
        if (CollectionUtils.isEmpty(points)) {
            return new DayBarResult(0);
        }

        Map<BucketKey, List<StockPricePoint>> grouped = points.stream().collect(Collectors.groupingBy(
                point -> new BucketKey(point.stocksId(), Stock15mBarBuildService.alignToBucket(point.time())),
                LinkedHashMap::new,
                Collectors.toList()));

        List<TornStockMarketBar15mDO> bars = new ArrayList<>(grouped.size());
        for (Map.Entry<BucketKey, List<StockPricePoint>> entry : grouped.entrySet()) {
            LocalDateTime bucketStart = entry.getKey().bucketStart();
            LocalDateTime bucketEnd = bucketStart.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
            TornStockMarketBar15mDO bar = Stock15mBarBuildService.buildSingleBar(
                    entry.getValue(), bucketStart, bucketEnd);
            if (bar != null) {
                bars.add(bar);
                barCountByBucket.merge(bucketStart, 1, Integer::sum);
            }
            actualBuckets.add(bucketStart);
        }

        if (!bars.isEmpty()) {
            upsertBarsInBatches(bars);
        }
        log.info("派生重建-bar日分片完成, dayStart={}, dayEnd={}, minutePoints={}, actualBuckets={}, bars={}",
                dayStart, dayEnd, points.size(), grouped.size(), bars.size());
        return new DayBarResult(bars.size());
    }

    /**
     * 重建单支股票在目标范围内的 feature。
     *
     * @param stock                股票
     * @param start                起始桶（含）
     * @param end                  结束桶（不含）
     * @param featureCountByBucket 每桶 feature 数映射（累积）
     * @return 该股票 feature 写入数
     */
    private int rebuildStockFeatures(TornStocksDO stock, LocalDateTime start, LocalDateTime end,
                                     Map<LocalDateTime, Integer> featureCountByBucket) {
        LocalDateTime scanStart = start.minusDays(FEATURE_WARMUP_DAYS);
        List<TornStockMarketBar15mDO> bars = bar15mDao.selectByStockAndTimeRange(
                stock.getId(), scanStart, end.minusNanos(1), Stock15mBarBuildService.BUILD_VERSION);
        if (CollectionUtils.isEmpty(bars)) {
            return 0;
        }

        Stock15mFeatureRollingWindow window = new Stock15mFeatureRollingWindow();
        List<TornStockStrategyFeature15mDO> features = new ArrayList<>();
        int featureWrites = 0;
        for (TornStockMarketBar15mDO bar : bars) {
            TornStockStrategyFeature15mDO feature = window.append(bar);
            if (feature != null && !bar.getBarStartTime().isBefore(start)) {
                features.add(feature);
                featureCountByBucket.merge(bar.getBarStartTime(), 1, Integer::sum);
                if (features.size() >= BATCH_SIZE) {
                    featureWrites += flushFeatures(features);
                }
            }
        }
        featureWrites += flushFeatures(features);
        log.info("派生重建-feature股票完成, stockId={}, stockName={}, scanBars={}, featureWrites={}",
                stock.getId(), stock.getStocksShortname(), bars.size(), featureWrites);
        return featureWrites;
    }

    /**
     * 将累积 feature 列表按批写出并清空。
     *
     * @param features 待写出 feature 列表（会清空）
     * @return 本次写出的 feature 条数
     */
    private int flushFeatures(List<TornStockStrategyFeature15mDO> features) {
        if (features.isEmpty()) {
            return 0;
        }
        List<TornStockStrategyFeature15mDO> batch = new ArrayList<>(features);
        feature15mDao.upsertFeatures(batch);
        features.clear();
        return batch.size();
    }

    /**
     * 将 bar 列表按固定批次 UPSERT。
     *
     * @param bars 待写入 bar 列表
     */
    private void upsertBarsInBatches(List<TornStockMarketBar15mDO> bars) {
        for (int i = 0; i < bars.size(); i += BATCH_SIZE) {
            List<TornStockMarketBar15mDO> batch = bars.subList(i, Math.min(i + BATCH_SIZE, bars.size()));
            bar15mDao.upsertBars(batch);
        }
    }

    /**
     * 将实际存在分钟事实的桶标记为 {@code REPAIRED_DATA_ONLY}。
     * <p>
     * 已处于 {@code COMPLETED} 或 {@code FAILED_FINAL} 的桶保留原终态，不降级。
     *
     * @param buckets              实际分钟桶集合
     * @param barCountByBucket     每桶 bar 数
     * @param featureCountByBucket 每桶 feature 数
     * @return 本次写入数据修复轮次数
     */
    private int markRoundsDataRepaired(Set<LocalDateTime> buckets,
                                       Map<LocalDateTime, Integer> barCountByBucket,
                                       Map<LocalDateTime, Integer> featureCountByBucket) {
        LocalDateTime now = marketClock.now();
        int count = 0;
        for (LocalDateTime bucket : buckets) {
            TornStockMarketRoundDO round = roundDao.selectByRoundTime(bucket);
            if (round != null && (StockRoundStatusEnum.COMPLETED.getCode().equals(round.getRoundStatus())
                    || StockRoundStatusEnum.FAILED_FINAL.getCode().equals(round.getRoundStatus()))) {
                log.info("派生重建-round保留原终态, bucket={}, status={}", bucket, round.getRoundStatus());
                continue;
            }
            TornStockMarketRoundDO target = round != null ? round : createDataRepairRound(bucket, now);
            target.setRoundStatus(StockRoundStatusEnum.REPAIRED_DATA_ONLY.getCode());
            target.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
            target.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
            target.setExpectedStockCount(barCountByBucket.getOrDefault(bucket, 0));
            target.setUsableStockCount(featureCountByBucket.getOrDefault(bucket, 0));
            if (target.getStartedAt() == null) {
                target.setStartedAt(now);
            }
            target.setCompletedAt(now);
            roundDao.updateById(target);
            count++;
        }
        log.info("派生重建-round标记完成, actualBuckets={}, dataOnlyRoundCount={}", buckets.size(), count);
        return count;
    }

    /**
     * 幂等创建数据修复轮次并返回持久化记录。
     *
     * @param bucket 桶开始时间
     * @param now    审计时间
     * @return 已持久化的轮次记录
     */
    private TornStockMarketRoundDO createDataRepairRound(LocalDateTime bucket, LocalDateTime now) {
        TornStockMarketRoundDO round = roundFactory.createRound(bucket, StockRoundStatusEnum.PENDING.getCode());
        round.setStartedAt(now);
        roundDao.insertPendingRoundIgnoreConflict(round);
        TornStockMarketRoundDO persisted = roundDao.selectByRoundTime(bucket);
        if (persisted == null) {
            throw new IllegalStateException("派生重建-轮次插入后无法查询: " + bucket);
        }
        return persisted;
    }

    /**
     * 计算范围内的 15 分钟桶总数。
     *
     * @param start 起始桶（含）
     * @param end   结束桶（不含）
     * @return 桶总数
     */
    private long bucketCount(LocalDateTime start, LocalDateTime end) {
        return Duration.between(start, end).toMinutes() / Stock15mBarBuildService.BUCKET_MINUTES;
    }

    /**
     * 构造带已有进度的失败结果。
     *
     * @param start       起始桶（含）
     * @param end         结束桶（不含）
     * @param progress    当前已处理进度
     * @param failedStart 失败分片起始（含）
     * @param failedEnd   失败分片结束（不含）
     * @param error       错误摘要
     * @param startNanos  开始纳秒时间
     * @return 失败重建结果
     */
    private StockDerivedDataRebuildResult failureWithProgress(LocalDateTime start, LocalDateTime end,
                                                              RebuildProgress progress,
                                                              LocalDateTime failedStart, LocalDateTime failedEnd,
                                                              String error, long startNanos) {
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        return new StockDerivedDataRebuildResult(start, end, progress.stockCount(),
                progress.processedBucketCount(), progress.barWrites(), progress.featureWrites(),
                0, Math.max(0, (int) (bucketCount(start, end) - progress.processedBucketCount())),
                elapsedMillis, failedStart, failedEnd, error);
    }

    /**
     * 构造尚未开始时即失败的简要结果。
     *
     * @param start       起始桶（含）
     * @param end         结束桶（不含）
     * @param failedStart 失败分片起始（含）
     * @param failedEnd   失败分片结束（不含）
     * @param error       错误摘要
     * @return 失败重建结果
     */
    private StockDerivedDataRebuildResult failure(LocalDateTime start, LocalDateTime end,
                                                  LocalDateTime failedStart, LocalDateTime failedEnd,
                                                  String error) {
        long elapsedMillis = 0;
        return new StockDerivedDataRebuildResult(start, end, 0, 0, 0, 0, 0, 0, elapsedMillis,
                failedStart, failedEnd, error);
    }

    /**
     * 重建进度快照。
     *
     * @param stockCount           有效股票数
     * @param processedBucketCount 已处理桶数
     * @param barWrites            bar 写入数
     * @param featureWrites        feature 写入数
     */
    private record RebuildProgress(int stockCount, int processedBucketCount, int barWrites, int featureWrites) {
        /**
         * 更新已处理桶数。
         *
         * @param processedBucketCount 已处理桶数
         * @return 新进度快照
         */
        RebuildProgress withProcessed(int processedBucketCount) {
            return new RebuildProgress(stockCount, processedBucketCount, barWrites, featureWrites);
        }

        /**
         * 更新 bar 写入数。
         *
         * @param barWrites bar 写入数
         * @return 新进度快照
         */
        RebuildProgress withBarWrites(int barWrites) {
            return new RebuildProgress(stockCount, processedBucketCount, barWrites, featureWrites);
        }

        /**
         * 更新 feature 写入数。
         *
         * @param featureWrites feature 写入数
         * @return 新进度快照
         */
        RebuildProgress withFeatureWrites(int featureWrites) {
            return new RebuildProgress(stockCount, processedBucketCount, barWrites, featureWrites);
        }
    }

    /**
     * 单步重建结果：成功时携带写入数，失败时携带失败分片与错误摘要。
     */
    private record RebuildStepOutcome(boolean success, int writes,
                                      LocalDateTime failedStart, LocalDateTime failedEnd, String error) {
        /**
         * 构造成功结果。
         *
         * @param writes 写入数
         * @return 成功结果
         */
        static RebuildStepOutcome success(int writes) {
            return new RebuildStepOutcome(true, writes, null, null, null);
        }

        /**
         * 构造失败结果。
         *
         * @param failedStart 失败分片起始（含）
         * @param failedEnd   失败分片结束（不含）
         * @param error       错误摘要
         * @return 失败结果
         */
        static RebuildStepOutcome failure(LocalDateTime failedStart, LocalDateTime failedEnd, String error) {
            return new RebuildStepOutcome(false, 0, failedStart, failedEnd, error);
        }
    }

    private record BucketKey(
            Integer stocksId,
            LocalDateTime bucketStart
    ) {
    }

    private record DayBarResult(
            int barWrites
    ) {
    }
}
