package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 15分钟bar构建服务 - 从分钟级历史采样按时区聚合为标准15分钟K线,并判定数据质量与连续性事实
 * <p>
 * 将 {@link TornStocksHistoryDAO} 中的分钟级价格采样按 {@value #ZONE_ID_TEXT} 时区的15分钟桶
 * 聚合为OHLC bar,严格执行去重(同一采集时间按最后一条保留)、采样数与尾部新鲜度校验,
 * 产出 {@link TornStockMarketBar15mDO} 供策略特征计算消费。不使用插值、前向填充或未来价格补齐。
 *
 * <h3>核心规则</h3>
 * <ul>
 *   <li>桶边界: B(T) = [T, T+15分钟), T对齐到每小时00/15/30/45分</li>
 *   <li>可用标准: sampleCount >= {@value #MIN_SAMPLE_COUNT} AND lastSampleTime >= barEnd - {@value #TAIL_FRESHNESS_MINUTES}分钟</li>
 *   <li>连续bar: B1仅在时间上紧邻B0且两者均可用时,才是B0的连续下一bar</li>
 *   <li>禁止依赖服务器或JVM默认时区,所有时间基于 {@value #ZONE_ID_TEXT}</li>
 * </ul>
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.07.24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stock15mBarBuildService {
    private final TornStocksHistoryDAO stocksHistoryDao;
    private final TornStockMarketBar15mDAO bar15mDao;

    /**
     * 产品时区文本(仅用于Javadoc展示)
     */
    static final String ZONE_ID_TEXT = "Asia/Shanghai";
    /**
     * 产品时区
     */
    public static final ZoneId ZONE_ID = ZoneId.of(ZONE_ID_TEXT);
    /**
     * 15分钟桶跨度(分钟)
     */
    public static final int BUCKET_MINUTES = 15;
    /**
     * bar可用最小采样数
     */
    public static final int MIN_SAMPLE_COUNT = 10;
    /**
     * 尾部新鲜度阈值(分钟): lastSampleTime >= barEnd - 此值
     */
    public static final int TAIL_FRESHNESS_MINUTES = 5;
    /**
     * bar构建规则版本
     */
    public static final String BUILD_VERSION = "1.0.0";
    /**
     * 质量原因: 采样数不足
     */
    public static final String QUALITY_REASON_SAMPLE_INSUFFICIENT = "SAMPLE_INSUFFICIENT";
    /**
     * 质量原因: 尾部缺口过大
     */
    public static final String QUALITY_REASON_TAIL_GAP_TOO_LARGE = "TAIL_GAP_TOO_LARGE";

    // ==================== 桶对齐 ====================

    /**
     * 将任意时间对齐到15分钟桶边界(向下载断)
     * <p>
     * 取分钟部分对齐到00/15/30/45,秒和纳秒清零。
     * 例如: 10:07:23 -> 10:00:00, 10:22:59 -> 10:15:00, 10:38:00 -> 10:30:00
     *
     * @param time 任意时间(不可为null)
     * @return 对齐后的桶开始时间
     */
    public static LocalDateTime alignToBucket(LocalDateTime time) {
        int minute = time.getMinute();
        int alignedMinute = minute / BUCKET_MINUTES * BUCKET_MINUTES;
        return time.withMinute(alignedMinute).withSecond(0).withNano(0);
    }

    // ==================== bar可用性判断 ====================

    /**
     * 判断bar是否满足可用标准
     * <p>
     * 同时满足: sampleCount >= {@value #MIN_SAMPLE_COUNT}
     * AND lastSampleTime >= barEndTime - {@value #TAIL_FRESHNESS_MINUTES}分钟
     *
     * @param bar 待判定的bar(为null时返回false)
     * @return true表示可用
     */
    public static boolean isUsable(TornStockMarketBar15mDO bar) {
        if (bar == null) {
            return false;
        }
        boolean sampleSufficient = bar.getSampleCount() != null
                && bar.getSampleCount() >= MIN_SAMPLE_COUNT;
        boolean tailFresh = bar.getLastSampleTime() != null
                && bar.getBarEndTime() != null
                && !bar.getLastSampleTime().isBefore(
                bar.getBarEndTime().minusMinutes(TAIL_FRESHNESS_MINUTES));
        return sampleSufficient && tailFresh;
    }

    /**
     * 判断两个bar是否构成连续关系
     * <p>
     * next仅在时间上紧邻prev(next.barStart == prev.barStart + 15分钟)
     * 且两者均满足可用标准时,才是prev的连续下一bar。更晚的可用bar不能替代紧邻下一bar。
     *
     * @param prev 前一个bar(为null时返回false)
     * @param next 后一个bar(为null时返回false)
     * @return true表示next是prev的连续下一bar
     */
    public static boolean isConsecutive(TornStockMarketBar15mDO prev, TornStockMarketBar15mDO next) {
        if (prev == null || next == null) {
            return false;
        }
        if (prev.getBarStartTime() == null || next.getBarStartTime() == null) {
            return false;
        }
        boolean timeAdjacent = next.getBarStartTime()
                .equals(prev.getBarStartTime().plusMinutes(BUCKET_MINUTES));
        boolean bothUsable = isUsable(prev) && isUsable(next);
        return timeAdjacent && bothUsable;
    }

    // ==================== bar构建 ====================

    /**
     * 构建指定桶的全部股票15分钟bar
     * <p>
     * 自动将bucketStartTime对齐到15分钟桶边界,查询该桶范围内的分钟级历史采样,
     * 按股票ID分组后逐股构建bar(去重、计算OHLC、判断可用性),最后批量保存。
     *
     * @param bucketStartTime 桶开始时间(无需预先对齐,方法内部会自动对齐)
     * @return 构建完成的bar列表(可能为空)
     */
    public List<TornStockMarketBar15mDO> buildBars(LocalDateTime bucketStartTime) {
        LocalDateTime barStart = alignToBucket(bucketStartTime);
        LocalDateTime barEnd = barStart.plusMinutes(BUCKET_MINUTES);
        log.debug("开始构建15分钟bar, barStart={}, barEnd={}", barStart, barEnd);

        List<StockPricePoint> points = stocksHistoryDao.selectHistoryPointsRange(barStart, barEnd);
        if (CollectionUtils.isEmpty(points)) {
            log.warn("桶[{}, {})无分钟采样数据,跳过bar构建", barStart, barEnd);
            return List.of();
        }

        Map<Integer, List<StockPricePoint>> groupedByStock = points.stream()
                .collect(Collectors.groupingBy(StockPricePoint::stocksId));
        log.debug("桶[{}, {})共获取{}条分钟采样,涉及{}支股票",
                barStart, barEnd, points.size(), groupedByStock.size());

        List<TornStockMarketBar15mDO> bars = new ArrayList<>(groupedByStock.size());
        for (Map.Entry<Integer, List<StockPricePoint>> entry : groupedByStock.entrySet()) {
            TornStockMarketBar15mDO bar = buildSingleBar(entry.getValue(), barStart, barEnd);
            if (bar != null) {
                bars.add(bar);
            }
        }

        if (bars.isEmpty()) {
            log.warn("桶[{}, {})构建bar结果为空", barStart, barEnd);
            return List.of();
        }

        bar15mDao.upsertBars(bars);
        log.debug("桶[{}, {})成功构建并保存{}支股票的15分钟bar", barStart, barEnd, bars.size());
        return bars;
    }

    // ==================== 私有方法 ====================

    /**
     * 为单支股票构建15分钟bar
     * <p>
     * 执行去重(同一采集时间按最后一条保留)、计算OHLC与采样统计、判断可用性,
     * 返回填充完整的 {@link TornStockMarketBar15mDO}。
     *
     * @param rawPoints 该股票在桶内的原始分钟采样(未去重)
     * @param barStart  桶开始时间
     * @param barEnd    桶结束时间
     * @return 构建完成的bar,原始数据为空时返回null
     */
    public static TornStockMarketBar15mDO buildSingleBar(List<StockPricePoint> rawPoints,
                                                         LocalDateTime barStart,
                                                         LocalDateTime barEnd) {
        DedupResult dedup = dedupByTime(rawPoints);
        List<StockPricePoint> uniquePoints = dedup.uniquePoints();
        if (uniquePoints.isEmpty()) {
            return null;
        }

        StockPricePoint first = uniquePoints.getFirst();
        StockPricePoint last = uniquePoints.getLast();
        BigDecimal lowPrice = uniquePoints.stream()
                .map(StockPricePoint::price)
                .min(BigDecimal::compareTo)
                .orElse(null);
        BigDecimal highPrice = uniquePoints.stream()
                .map(StockPricePoint::price)
                .max(BigDecimal::compareTo)
                .orElse(null);

        Integer tailGapSeconds = null;
        if (last.time() != null) {
            tailGapSeconds = (int) Duration.between(last.time(), barEnd).getSeconds();
        }

        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(first.stocksId());
        bar.setStocksShortname(first.stocksShortname());
        bar.setBarStartTime(barStart);
        bar.setBarEndTime(barEnd);
        bar.setFirstSampleTime(first.time());
        bar.setLastSampleTime(last.time());
        bar.setFirstPrice(first.price());
        bar.setLastPrice(last.price());
        bar.setLowPrice(lowPrice);
        bar.setHighPrice(highPrice);
        bar.setSampleCount(uniquePoints.size());
        bar.setDuplicateCount(dedup.duplicateCount());
        bar.setTailGapSeconds(tailGapSeconds);
        bar.setBuildVersion(BUILD_VERSION);
        bar.setSourceMaxHistoryId(dedup.maxHistoryId());

        evaluateUsability(bar);
        return bar;
    }

    /**
     * 对同一股票的分钟采样按采集时间去重,保留每个时间的最大ID记录
     * <p>
     * SQL已按stocks_id, reg_date_time, id排序,同一时间的后续记录ID更大。
     * 使用LinkedHashMap保留插入顺序,同一时间的后续(更大ID)记录覆盖前一条。
     *
     * @param rawPoints 原始采样列表(已按id升序排序)
     * @return 去重结果(去重后列表 + 重复数量 + 最大历史ID)
     */
    private static DedupResult dedupByTime(List<StockPricePoint> rawPoints) {
        int totalCount = rawPoints.size();
        LinkedHashMap<LocalDateTime, StockPricePoint> byTime = new LinkedHashMap<>();
        Long maxHistoryId = null;
        for (StockPricePoint point : rawPoints) {
            byTime.put(point.time(), point);
            if (point.id() != null && (maxHistoryId == null || point.id() > maxHistoryId)) {
                maxHistoryId = point.id();
            }
        }
        List<StockPricePoint> unique = new ArrayList<>(byTime.values());
        int duplicateCount = totalCount - unique.size();
        return new DedupResult(unique, duplicateCount, maxHistoryId);
    }

    /**
     * 评估bar可用性并设置usable与qualityReason字段
     * <p>
     * 判断逻辑:
     * <ol>
     *   <li>采样数 < {@value #MIN_SAMPLE_COUNT} -> SAMPLE_INSUFFICIENT</li>
     *   <li>lastSampleTime < barEnd - {@value #TAIL_FRESHNESS_MINUTES}分钟 -> TAIL_GAP_TOO_LARGE</li>
     *   <li>同时满足两个条件 -> usable=true, qualityReason=null</li>
     * </ol>
     *
     * @param bar 待评估的bar(会修改usable和qualityReason字段)
     */
    private static void evaluateUsability(TornStockMarketBar15mDO bar) {
        boolean sampleSufficient = bar.getSampleCount() != null
                && bar.getSampleCount() >= MIN_SAMPLE_COUNT;
        boolean tailFresh = bar.getLastSampleTime() != null
                && bar.getBarEndTime() != null
                && !bar.getLastSampleTime().isBefore(
                bar.getBarEndTime().minusMinutes(TAIL_FRESHNESS_MINUTES));

        if (sampleSufficient && tailFresh) {
            bar.setUsable(true);
            bar.setQualityReason(null);
            return;
        }

        bar.setUsable(false);
        if (!sampleSufficient) {
            bar.setQualityReason(QUALITY_REASON_SAMPLE_INSUFFICIENT);
        } else {
            bar.setQualityReason(QUALITY_REASON_TAIL_GAP_TOO_LARGE);
        }
    }

    /**
     * 去重结果值对象
     *
     * @param uniquePoints   去重后的采样列表(按时间升序)
     * @param duplicateCount 被去除的重复记录数量
     * @param maxHistoryId   本桶使用的最大原始历史ID
     */
    private record DedupResult(
            List<StockPricePoint> uniquePoints,
            int duplicateCount,
            Long maxHistoryId) {
    }
}
