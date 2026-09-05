package pn.torn.goldeneye.torn.service.stocks.alert.alpha.market;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDailySnapshotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDailySnapshotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockAlphaDailyCloseService {
    private static final String BAR_BUILD_VERSION = "1.2.0";
    private final TornStockMarketBar15mDAO barDao;
    private final TornStockAlphaDailySnapshotDAO snapshotDao;
    private final StockMarketClock marketClock;

    public Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> loadDailyCloses(
            LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(StockAlphaRuleDefinition.WARMUP_COMMON_DAYS + 20L);
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        List<TornStockMarketBar15mDO> bars = barDao.selectByStocksAndTimeRange(
                StockAlphaRuleDefinition.stockUniverse(), start, end, BAR_BUILD_VERSION);
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> result = bars.stream().filter(bar -> bar.getStocksId() != null)
                .collect(Collectors.groupingBy(bar -> bar.getBarStartTime().toLocalDate(),
                        Collectors.groupingBy(TornStockMarketBar15mDO::getStocksId,
                                Collectors.collectingAndThen(Collectors.toList(), values ->
                                        StockAlphaDailyCloseCalculator.calculate(values.getFirst().getBarStartTime().toLocalDate(), values)))));
        result.values().forEach(daily -> daily.values().forEach(this::persistSnapshot));
        return result;
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

    public List<LocalDate> loadCommonValidDates(LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(StockAlphaRuleDefinition.WARMUP_COMMON_DAYS + 20L);
        return snapshotDao.selectCommonValidDates(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION,
                StockAlphaRuleDefinition.RULE_VERSION, StockAlphaRuleDefinition.MEMBER_COUNT, startDate, endDate);
    }

    public LocalDate latestEndedDate() {
        return marketClock.currentEndedBucket().toLocalDate();
    }
}
