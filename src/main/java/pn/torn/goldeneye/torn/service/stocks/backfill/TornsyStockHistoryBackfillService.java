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
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockHistoryRebuildService;

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
 * 外网调用不进入 VIP 轮次事务，按股票 × 非饱和时间片（默认最多 900 分钟）串行请求；每个时间片先拉取全部原始响应，确认无满页后再解析与短事务写入。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornsyStockHistoryBackfillService {

    /**
     * 单个时间片的最大时长（分钟）：默认 pageLimit=1000 时最多 900 分钟，确保永不满页。
     */
    private static final int MAX_SLICE_MINUTES = 900;
    /**
     * 后向 feature 重算窗口（天）：feature 最大回看窗口 30 天
     */
    private static final int FEATURE_REBUILD_WINDOW_DAYS = 30;

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
     * 按非饱和时间片切分，每个时间片先拉取全部股票的原始响应（阶段A）；
     * 仅当全部响应行数都小于 pageLimit 时，才读取已存在分钟并逐股解析、冲突安全写入（阶段B），
     * 收集实际插入分钟所属 15 分钟桶，最后合并相邻桶并定向重建派生数据。
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
        String runId = generateRunId();
        int pageLimit = property.getPageLimit();
        if (pageLimit <= 1) {
            throw new IllegalArgumentException("Tornsy分页大小必须大于1: " + pageLimit);
        }
        int sliceMinutes = Math.min(MAX_SLICE_MINUTES, pageLimit - 1);
        LocalDateTime latestHistoryTime = stocksHistoryDao.selectLatestHistoryTime();
        log.info("历史回填-开始, 区间=[{}, {}), 股票数={}, 当前最新历史时间={}, runId={}",
                startInclusive, endExclusive, stocks.size(), latestHistoryTime, runId);

        LocalDateTime sliceStart = startInclusive;
        while (sliceStart.isBefore(endExclusive)) {
            LocalDateTime sliceEnd = sliceStart.plusMinutes(sliceMinutes);
            if (sliceEnd.isAfter(endExclusive)) {
                sliceEnd = endExclusive;
            }
            boolean saturated = backfillSlice(stocks, stocksIds, sliceStart, sliceEnd, endExclusive, acc);
            if (saturated) {
                log.error("历史回填-时间片达到pageLimit上限, 停止后续分片, sliceStart={}, sliceEnd={}, pageLimit={}",
                        sliceStart, sliceEnd, pageLimit);
                break;
            }
            sliceStart = sliceEnd;
        }

        int rebuilt = repairAffectedHistory(acc.affectedBuckets, runId);
        BackfillSummary summary = acc.toSummary(rebuilt);
        log.info("历史回填完成, 区间=[{}, {}), sourceRows={}, validRows={}, rejectedRows={}, "
                        + "existedSkippedRows={}, insertedRows={}, failedSlices={}, "
                        + "affectedBucketCount={}, rebuiltBucketCount={}, runId={}",
                startInclusive, endExclusive, summary.sourceRows(), summary.validRows(),
                summary.rejectedRows(), summary.existedSkippedRows(), summary.insertedRows(),
                summary.failedSlices(), summary.affectedBucketCount(), summary.rebuiltBucketCount(), runId);
        return summary;
    }

    /**
     * 回填单个时间片：阶段A只拉取原始响应，阶段B在无满页时解析与写入。
     *
     * @return true 表示本时间片响应达到 pageLimit 上限，应停止后续分片
     */
    private boolean backfillSlice(List<TornStocksDO> stocks, List<Integer> stocksIds,
                                  LocalDateTime sliceStart, LocalDateTime sliceEnd,
                                  LocalDateTime stableEndExclusive, BackfillAccumulator acc) {
        long fromEpoch = sliceStart.atZone(TornsyMinuteQuoteParser.ZONE_ID).toEpochSecond();
        long toEpoch = sliceEnd.atZone(TornsyMinuteQuoteParser.ZONE_ID).toEpochSecond();
        int pageLimit = property.getPageLimit();
        List<List<JsonNode>> responses = new ArrayList<>();
        boolean saturated = false;

        // 阶段A：只拉取原始响应，不 parser、不查询/写入候选、不触发重建
        for (TornStocksDO stock : stocks) {
            try {
                List<JsonNode> rows = client.fetchMinuteData(stock.getStocksShortname(), fromEpoch, toEpoch, pageLimit);
                if (rows.size() >= pageLimit) {
                    acc.failedSlices++;
                    saturated = true;
                    log.error("回填股票时间片响应满页, 当前切片失败并停止后续切片, 股票={}, 时间片=[{}, {})",
                            stock.getStocksShortname(), sliceStart, sliceEnd);
                    break;
                }
                responses.add(rows);
            } catch (Exception e) {
                acc.failedSlices++;
                responses.add(null);
                log.warn("回填股票时间片拉取失败, 股票={}, 时间片=[{}, {}): {}",
                        stock.getStocksShortname(), sliceStart, sliceEnd, e.getMessage());
            }
        }
        if (saturated) {
            return true;
        }
        if (responses.isEmpty()) {
            return false;
        }

        // 阶段B：仅当全部响应 rows < pageLimit 时才读取已有分钟并逐股解析/写入
        Set<StockHistoryMinuteSlot> existing = loadExistingSlots(stocksIds, sliceStart, sliceEnd);
        for (int i = 0; i < stocks.size(); i++) {
            List<JsonNode> rows = responses.get(i);
            if (rows == null) {
                continue;
            }
            TornStocksDO stock = stocks.get(i);
            try {
                processFetchedSlice(stock, rows, sliceStart, sliceEnd, stableEndExclusive, existing, acc);
            } catch (Exception e) {
                acc.failedSlices++;
                log.warn("回填股票时间片处理失败, 股票={}, 时间片=[{}, {}): {}",
                        stock.getStocksShortname(), sliceStart, sliceEnd, e.getMessage());
            }
        }
        return false;
    }

    /**
     * 处理阶段A已拉取且确认非满页的单股票响应：解析、校验、去重、过滤与冲突安全写入。
     */
    private void processFetchedSlice(TornStocksDO stock, List<JsonNode> rows,
                                     LocalDateTime sliceStart, LocalDateTime sliceEnd,
                                     LocalDateTime stableEndExclusive,
                                     Set<StockHistoryMinuteSlot> existing, BackfillAccumulator acc) {
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

        List<StockHistoryMinuteSlot> insertedSlots = List.of();
        if (!toInsert.isEmpty()) {
            insertedSlots = stocksHistoryDao.insertBackfillReturningSlots(toInsert);
        }
        acc.insertedRows += insertedSlots.size();
        acc.existedSkippedRows += existedByQuery + (toInsert.size() - insertedSlots.size());

        for (StockHistoryMinuteSlot slot : insertedSlots) {
            acc.affectedBuckets.add(Stock15mBarBuildService.alignToBucket(slot.minuteTime()));
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
     * 由实际插入分钟驱动派生数据修复：受影响桶强制 bar 重建 + 后向 30 天 feature 重算。
     * <p>
     * 仅对实际插入槽所属桶调用 {@link StockHistoryRebuildService#repairBackfilledHistory(
     *Collection, LocalDateTime, String)},冲突跳过行不产生重建义务;
     * feature 重算范围从最早受影响桶到 {@code latestAffected + 30天 + 15分钟}(不含)。
     *
     * @param affectedBuckets 实际插入分钟所属的受影响桶集合
     * @param runId           本次回填运行标识
     * @return 重建桶总数
     */
    private int repairAffectedHistory(Set<LocalDateTime> affectedBuckets, String runId) {
        if (affectedBuckets.isEmpty()) {
            log.info("历史回填-无实际插入分钟, 无需派生数据修复, runId={}", runId);
            return 0;
        }
        LocalDateTime latestAffected = affectedBuckets.stream().max(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime featureRebuildEndExclusive = Stock15mBarBuildService.alignToBucket(latestAffected)
                .plusDays(FEATURE_REBUILD_WINDOW_DAYS)
                .plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
        StockHistoryRebuildService.BackfillRepairResult result = rebuildService.repairBackfilledHistory(
                affectedBuckets, featureRebuildEndExclusive, runId);
        log.info("历史回填-派生数据修复完成, runId={}, affectedBucketCount={}, featureRebuildEndExclusive={}, "
                        + "forcedBarBuckets={}, dataOnlyRoundCount={}, recomputedFeatureBuckets={}, "
                        + "skippedNoBarBuckets={}, rebuiltBucketCount={}",
                runId, affectedBuckets.size(), featureRebuildEndExclusive,
                result.forcedBarBuckets(), result.dataOnlyRoundCount(),
                result.recomputedFeatureBuckets(), result.skippedNoBarBuckets(), result.rebuiltBucketCount());
        return result.rebuiltBucketCount();
    }

    /**
     * 生成本次回填运行标识（短 UUID,仅用于进度日志关联）
     *
     * @return 运行标识
     */
    private String generateRunId() {
        return UUID.randomUUID().toString().substring(0, 8);
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
