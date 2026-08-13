package pn.torn.goldeneye.torn.service.stocks.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.configuration.property.StockHistoryBackfillProperty;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockHistoryMinuteSlot;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksHistoryDO;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockHistoryRebuildService;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Tornsy 股票历史缺口回填服务 - 拉取、校验、同分钟去重、冲突安全写入与定向重建
 * <p>
 * 从 Tornsy m1 接口读取真实分钟点，补入 {@code torn_stocks_history}（来源
 * {@code TORNSY_BACKFILL}），只补缺不覆盖，未知市值/投资人写入 {@code NULL}；
 * 实际插入后定向重建受影响的 15 分钟 bar/feature/round。绕开 {@code TornStocksManager}：
 * 不更新 {@code torn_stocks}、不发送大额交易消息、不推进旧分钟特征游标。
 *
 * <h3>幂等与并发</h3>
 * 候选先按 {@code (stocksId, minuteTime)} 内存去重，再按批量存在性查询过滤，
 * 最终以 {@code INSERT ... ON CONFLICT DO NOTHING} 匹配自然分钟部分唯一索引兜底；
 * 外网调用不进入 VIP 轮次事务，按股票 × 最多 1 天时间片串行请求、短事务写入。
 *
 * @author Bai
 * @version 1.2.15
 * @since 2026.08.13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornsyStockHistoryBackfillService {

    /**
     * 单个时间片的最大时长（小时）
     */
    private static final int SLICE_HOURS = 24;
    /**
     * 重建区间单次最大跨度（天）
     */
    private static final int REBUILD_MAX_DAYS = 1;

    private final TornStocksDAO stocksDao;
    private final TornStocksHistoryDAO stocksHistoryDao;
    private final TornsyStockHistoryClient client;
    private final TornsyMinuteQuoteParser parser;
    private final StockHistoryRebuildService rebuildService;
    private final StockHistoryBackfillProperty property;

    /**
     * 回填当前全部有效股票在指定时间范围内的历史缺口
     *
     * @param startInclusive 起始时间（含，左闭右开）
     * @param endExclusive   结束时间（不含，应为稳定截止时间）
     * @return 回填汇总
     */
    public BackfillSummary backfillRange(LocalDateTime startInclusive, LocalDateTime endExclusive) {
        List<TornStocksDO> stocks = stocksDao.list();
        return backfillStocks(stocks, startInclusive, endExclusive);
    }

    /**
     * 回填指定股票集合在指定时间范围内的历史缺口
     * <p>
     * 按 1 天时间片切分，每个时间片先全股票批量读取已存在分钟（避免 N+1），
     * 再逐股请求、校验、冲突安全写入，收集实际插入分钟所属 15 分钟桶，
     * 最后合并相邻桶并定向重建派生数据。
     *
     * @param stocks         待回填股票集合
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含，应为稳定截止时间）
     * @return 回填汇总
     */
    public BackfillSummary backfillStocks(List<TornStocksDO> stocks, LocalDateTime startInclusive,
                                          LocalDateTime endExclusive) {
        if (stocks == null || stocks.isEmpty() || startInclusive == null || endExclusive == null) {
            return BackfillSummary.empty();
        }
        if (!startInclusive.isBefore(endExclusive)) {
            log.warn("回填区间无效, start={}, end={}, 起始时间需早于结束时间", startInclusive, endExclusive);
            return BackfillSummary.empty();
        }

        List<Integer> stocksIds = stocks.stream().map(TornStocksDO::getId).toList();
        BackfillAccumulator acc = new BackfillAccumulator();
        LocalDateTime latestHistoryTime = stocksHistoryDao.selectLatestHistoryTime();
        log.info("历史回填-开始, 区间=[{}, {}), 股票数={}, 当前最新历史时间={}",
                startInclusive, endExclusive, stocks.size(), latestHistoryTime);

        LocalDateTime sliceStart = startInclusive;
        while (sliceStart.isBefore(endExclusive)) {
            LocalDateTime sliceEnd = sliceStart.plusHours(SLICE_HOURS);
            if (sliceEnd.isAfter(endExclusive)) {
                sliceEnd = endExclusive;
            }
            backfillDaySlice(stocks, stocksIds, sliceStart, sliceEnd, endExclusive, acc);
            sliceStart = sliceEnd;
        }

        int rebuilt = rebuildAffectedBuckets(acc.affectedBuckets);
        BackfillSummary summary = acc.toSummary(rebuilt);
        log.info("历史回填完成, 区间=[{}, {}), sourceRows={}, validRows={}, rejectedRows={}, "
                        + "existedSkippedRows={}, insertedRows={}, failedSlices={}, "
                        + "affectedBucketCount={}, rebuiltBucketCount={}",
                startInclusive, endExclusive, summary.sourceRows(), summary.validRows(),
                summary.rejectedRows(), summary.existedSkippedRows(), summary.insertedRows(),
                summary.failedSlices(), summary.affectedBucketCount(), summary.rebuiltBucketCount());
        return summary;
    }

    /**
     * 回填单个 1 天时间片：先全股票批量读取已存在分钟，再逐股请求与写入
     */
    private void backfillDaySlice(List<TornStocksDO> stocks, List<Integer> stocksIds,
                                  LocalDateTime sliceStart, LocalDateTime sliceEnd,
                                  LocalDateTime stableEndExclusive, BackfillAccumulator acc) {
        Set<StockHistoryMinuteSlot> existing = loadExistingSlots(stocksIds, sliceStart, sliceEnd);
        for (TornStocksDO stock : stocks) {
            try {
                backfillStockSlice(stock, sliceStart, sliceEnd, stableEndExclusive, existing, acc);
            } catch (Exception e) {
                acc.failedSlices++;
                log.warn("回填股票时间片失败, 股票={}, 时间片=[{}, {}): {}",
                        stock.getStocksShortname(), sliceStart, sliceEnd, e.getMessage());
            }
        }
    }

    /**
     * 回填单支股票单个时间片：请求、校验、映射、内存去重、存在性过滤与冲突安全写入
     */
    private void backfillStockSlice(TornStocksDO stock, LocalDateTime sliceStart, LocalDateTime sliceEnd,
                                    LocalDateTime stableEndExclusive,
                                    Set<StockHistoryMinuteSlot> existing, BackfillAccumulator acc) {
        long fromEpoch = sliceStart.atZone(TornsyMinuteQuoteParser.ZONE_ID).toEpochSecond();
        long toEpoch = sliceEnd.atZone(TornsyMinuteQuoteParser.ZONE_ID).toEpochSecond();

        List<JsonNode> rows = client.fetchMinuteData(stock.getStocksShortname(), fromEpoch, toEpoch,
                property.getPageLimit());
        acc.sourceRows += rows.size();

        List<TornsyMinuteQuote> quotes = parser.parse(rows, sliceStart, sliceEnd, stableEndExclusive);
        acc.validRows += quotes.size();
        acc.rejectedRows += rows.size() - quotes.size();
        if (quotes.isEmpty()) {
            return;
        }

        List<TornStocksHistoryDO> candidates = dedupByMinute(quotes.stream()
                .map(q -> toHistoryDO(stock, q))
                .toList());

        List<TornStocksHistoryDO> toInsert = candidates.stream()
                .filter(c -> !existing.contains(new StockHistoryMinuteSlot(c.getStocksId(), c.getRegDateTime())))
                .toList();
        int existedByQuery = candidates.size() - toInsert.size();

        int inserted = 0;
        if (!toInsert.isEmpty()) {
            inserted = stocksHistoryDao.insertBackfillIgnoreConflict(toInsert);
        }
        acc.insertedRows += inserted;
        acc.existedSkippedRows += existedByQuery + (toInsert.size() - inserted);

        for (TornStocksHistoryDO candidate : toInsert) {
            acc.affectedBuckets.add(Stock15mBarBuildService.alignToBucket(candidate.getRegDateTime()));
        }
    }

    /**
     * 将 m1 报价转换为历史 DO（来源 TORNSY_BACKFILL，投资人固定 NULL）
     */
    private TornStocksHistoryDO toHistoryDO(TornStocksDO stock, TornsyMinuteQuote quote) {
        TornStocksHistoryDO history = new TornStocksHistoryDO();
        history.setStocksId(stock.getId());
        history.setStocksName(stock.getStocksName());
        history.setStocksShortname(stock.getStocksShortname());
        history.setCurrentPrice(quote.price());
        history.setTotalShares(quote.totalShares());
        history.setMarketCap(quote.marketCap());
        history.setInvestors(null);
        history.setRegDateTime(quote.minuteTime());
        history.setDataSource(StockHistoryDataSourceEnum.TORNSY_BACKFILL.getCode());
        return history;
    }

    /**
     * 按自然分钟内存去重，保留首条
     */
    private List<TornStocksHistoryDO> dedupByMinute(List<TornStocksHistoryDO> candidates) {
        Map<LocalDateTime, TornStocksHistoryDO> byMinute = new LinkedHashMap<>();
        for (TornStocksHistoryDO candidate : candidates) {
            byMinute.putIfAbsent(candidate.getRegDateTime(), candidate);
        }
        return new ArrayList<>(byMinute.values());
    }

    /**
     * 批量读取已存在的自然分钟槽位，避免无效写入
     */
    private Set<StockHistoryMinuteSlot> loadExistingSlots(List<Integer> stocksIds,
                                                          LocalDateTime start, LocalDateTime end) {
        return new HashSet<>(stocksHistoryDao.selectExistingMinuteSlots(stocksIds, start, end));
    }

    /**
     * 合并相邻受影响桶为区间并定向重建派生数据，单次最多 1 天桶
     */
    private int rebuildAffectedBuckets(Set<LocalDateTime> affectedBuckets) {
        if (affectedBuckets.isEmpty()) {
            return 0;
        }
        List<LocalDateTime> sorted = affectedBuckets.stream().sorted().toList();
        int rebuilt = 0;
        LocalDateTime intervalStart = null;
        LocalDateTime lastBucket = null;
        for (LocalDateTime bucket : sorted) {
            if (intervalStart == null) {
                intervalStart = bucket;
                lastBucket = bucket;
                continue;
            }
            boolean adjacent = bucket.equals(lastBucket.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES));
            boolean withinDay = bucket.isBefore(intervalStart.plusDays(REBUILD_MAX_DAYS));
            if (!adjacent || !withinDay) {
                rebuilt += rebuildService.rebuildHistory(intervalStart,
                        lastBucket.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES));
                intervalStart = bucket;
            }
            lastBucket = bucket;
        }
        if (intervalStart != null) {
            rebuilt += rebuildService.rebuildHistory(intervalStart,
                    lastBucket.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES));
        }
        return rebuilt;
    }

    /**
     * 回填汇总（供调度层日志与验收使用）
     *
     * @param sourceRows          数据源返回的原始行数
     * @param validRows           校验通过的合法行数
     * @param existedSkippedRows  已存在而跳过的行数（存在性过滤 + 冲突跳过 + 内存去重）
     * @param insertedRows        实际插入行数
     * @param rejectedRows        校验拒绝行数
     * @param failedSlices        失败的时间片数量
     * @param affectedBucketCount 实际插入分钟影响的 15 分钟桶数量
     * @param rebuiltBucketCount  实际重建的桶数量
     */
    public record BackfillSummary(
            int sourceRows,
            int validRows,
            int existedSkippedRows,
            int insertedRows,
            int rejectedRows,
            int failedSlices,
            int affectedBucketCount,
            int rebuiltBucketCount) {

        /**
         * 空汇总
         *
         * @return 全零汇总
         */
        static BackfillSummary empty() {
            return new BackfillSummary(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * 回填过程可变累加器
     */
    private static final class BackfillAccumulator {
        private int sourceRows;
        private int validRows;
        private int existedSkippedRows;
        private int insertedRows;
        private int rejectedRows;
        private int failedSlices;
        private final Set<LocalDateTime> affectedBuckets = new HashSet<>();

        /**
         * 汇总为不可变结果
         *
         * @param rebuiltBucketCount 实际重建桶数量
         * @return 汇总结果
         */
        BackfillSummary toSummary(int rebuiltBucketCount) {
            return new BackfillSummary(sourceRows, validRows, existedSkippedRows, insertedRows,
                    rejectedRows, failedSlices, affectedBuckets.size(), rebuiltBucketCount);
        }
    }
}
