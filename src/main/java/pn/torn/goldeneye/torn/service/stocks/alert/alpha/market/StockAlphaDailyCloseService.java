package pn.torn.goldeneye.torn.service.stocks.alert.alpha.market;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDailySnapshotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDailySnapshotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * α策略日线收盘服务。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Service
@RequiredArgsConstructor
public class StockAlphaDailyCloseService {
    /**
     * 收盘bar构建版本。
     */
    private static final String BAR_BUILD_VERSION = "1.2.0";
    /**
     * 15分钟bar数据访问对象。
     */
    private final TornStockMarketBar15mDAO barDao;
    /**
     * α日线快照数据访问对象。
     */
    private final TornStockAlphaDailySnapshotDAO snapshotDao;
    /**
     * 行情时钟。
     */
    private final StockMarketClock marketClock;

    /**
     * 加载指定结束日期前的日线收盘数据并持久化快照。
     *
     * @param endDate 结束日期
     * @return 按日期和股票ID分组的收盘结果
     */
    public Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> loadDailyCloses(
            LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(StockAlphaRuleDefinition.WARMUP_COMMON_DAYS + 20L);
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        List<TornStockMarketBar15mDO> bars = barDao.selectByStocksAndTimeRange(
                StockAlphaRuleDefinition.stockUniverse(), start, end, BAR_BUILD_VERSION);
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> result = bars.stream()
                .filter(bar -> bar.getStocksId() != null)
                .collect(Collectors.groupingBy(bar -> bar.getBarStartTime().toLocalDate(),
                        Collectors.groupingBy(TornStockMarketBar15mDO::getStocksId,
                                Collectors.collectingAndThen(Collectors.toList(), values ->
                                        StockAlphaDailyCloseCalculator.calculate(
                                                values.getFirst().getBarStartTime().toLocalDate(), values)))));
        result.values().forEach(daily -> daily.values().forEach(this::persistSnapshot));
        return result;
    }

    /**
     * 持久化指定日期的排名结果。
     *
     * @param daily    日线收盘结果
     * @param rankings 排名结果
     */
    public void persistRankings(Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily,
                                List<StockAlphaRankingResult> rankings) {
        if (daily == null || rankings == null || rankings.isEmpty()) {
            return;
        }
        LocalDate latestDate = daily.keySet().stream().max(LocalDate::compareTo).orElse(null);
        if (latestDate == null) {
            return;
        }
        Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> latest = daily.get(latestDate);
        rankings.forEach(ranking -> persistRanking(latestDate, latest.get(ranking.stocksId()), ranking));
    }

    private void persistSnapshot(StockAlphaDailyCloseCalculator.CloseResult close) {
        if (close == null) {
            return;
        }
        TornStockAlphaDailySnapshotDO snapshot = new TornStockAlphaDailySnapshotDO();
        snapshot.setStocksId(close.stocksId());
        snapshot.setBusinessDate(close.businessDate());
        snapshot.setClosePrice(close.closePrice());
        snapshot.setSourceBarId(close.sourceBarId());
        snapshot.setSourceBarStartTime(close.sourceBarStartTime());
        snapshot.setStockUniverseVersion(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION);
        snapshot.setAlphaRuleVersion(StockAlphaRuleDefinition.RULE_VERSION);
        snapshot.setCommonValid(true);
        snapshotDao.insertIgnoreConflict(snapshot);
    }

    private void persistRanking(LocalDate rankingDate,
                                StockAlphaDailyCloseCalculator.CloseResult close,
                                StockAlphaRankingResult ranking) {
        if (close == null || ranking == null || !rankingDate.equals(close.businessDate())) {
            return;
        }
        TornStockAlphaDailySnapshotDO snapshot = new TornStockAlphaDailySnapshotDO();
        snapshot.setStocksId(close.stocksId());
        snapshot.setBusinessDate(close.businessDate());
        snapshot.setClosePrice(close.closePrice());
        snapshot.setSourceBarId(close.sourceBarId());
        snapshot.setSourceBarStartTime(close.sourceBarStartTime());
        snapshot.setStockUniverseVersion(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION);
        snapshot.setAlphaRuleVersion(StockAlphaRuleDefinition.RULE_VERSION);
        snapshot.setR20(ranking.r20());
        snapshot.setR1(ranking.r1());
        snapshot.setR20Rank(ranking.r20Rank());
        snapshot.setR1Rank(ranking.r1Rank());
        snapshot.setR20Normalized(ranking.r20Rank());
        snapshot.setR1Normalized(ranking.r1Rank());
        snapshot.setAlphaScore(ranking.alphaScore());
        snapshot.setRankPosition(ranking.rankPosition());
        snapshot.setCommonValid(true);
        snapshotDao.insertIgnoreConflict(snapshot);
    }

    /**
     * 查询共同有效日期。
     *
     * @param endDate 结束日期
     * @return 共同有效日期
     */
    public List<LocalDate> loadCommonValidDates(LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(StockAlphaRuleDefinition.WARMUP_COMMON_DAYS + 20L);
        return snapshotDao.selectCommonValidDates(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION,
                StockAlphaRuleDefinition.RULE_VERSION, StockAlphaRuleDefinition.MEMBER_COUNT, startDate, endDate);
    }

    /**
     * 获取最近结束的行情日期。
     *
     * @return 最近结束日期
     */
    public LocalDate latestEndedDate() {
        return marketClock.currentEndedBucket().toLocalDate();
    }
}
