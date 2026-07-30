package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalStateDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;

import java.util.List;
import java.util.Objects;

/**
 * 回放只读输入加载器。
 *
 * <p>只调用批量查询，不执行任何写入和正式业务编排。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public class StockReplayInputLoader {
    private final TornStockMarketBar15mDAO barDao;
    private final TornStockStrategyFeature15mDAO featureDao;
    private final TornStockMonthlyStateDAO monthlyStateDao;
    private final TornStockSignalStateDAO signalStateDao;

    public StockReplayInputLoader(TornStockMarketBar15mDAO barDao,
                                  TornStockStrategyFeature15mDAO featureDao) {
        this(barDao, featureDao, null, null);
    }

    public StockReplayInputLoader(TornStockMarketBar15mDAO barDao,
                                  TornStockStrategyFeature15mDAO featureDao,
                                  TornStockMonthlyStateDAO monthlyStateDao,
                                  TornStockSignalStateDAO signalStateDao) {
        this.barDao = Objects.requireNonNull(barDao, "barDao不能为空");
        this.featureDao = Objects.requireNonNull(featureDao, "featureDao不能为空");
        this.monthlyStateDao = monthlyStateDao;
        this.signalStateDao = signalStateDao;
    }

    /**
     * 批量加载请求时间范围内的全部回放事实。
     *
     * @param request 回放请求
     * @return 只读输入
     */
    public StockReplayInput load(StockReplayRequest request) {
        Objects.requireNonNull(request, "回放请求不能为空");
        return new StockReplayInput(
                barDao.selectByTimeRange(request.startTime(), request.endTime(), request.barBuildVersion()),
                featureDao.selectByTimeRange(request.startTime(), request.endTime(), request.featureVersion()),
                loadMonthlyStates(request), loadSignalStates());
    }

    /**
     * 批量加载指定股票和时间范围的回放事实。
     *
     * @param request 回放请求
     * @param stocksIds 股票集合
     * @return 只读输入
     */
    public StockReplayInput load(StockReplayRequest request, List<Integer> stocksIds) {
        Objects.requireNonNull(request, "回放请求不能为空");
        if (stocksIds == null || stocksIds.isEmpty()) {
            throw new IllegalArgumentException("stocksIds不能为空");
        }
        List<Integer> normalizedStocksIds = stocksIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedStocksIds.isEmpty()) {
            throw new IllegalArgumentException("stocksIds不能全部为空");
        }
        return new StockReplayInput(
                barDao.selectByStocksAndTimeRange(normalizedStocksIds, request.startTime(), request.endTime(),
                        request.barBuildVersion()),
                featureDao.selectLatestByStocksIds(normalizedStocksIds, request.endTime(),
                        request.featureVersion()), loadMonthlyStates(request), loadSignalStates());
    }

    private List<pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO>
    loadMonthlyStates(StockReplayRequest request) {
        if (monthlyStateDao == null) {
            return List.of();
        }
        return monthlyStateDao.selectConfirmedByMonth(request.startTime().toLocalDate().withDayOfMonth(1));
    }

    private List<pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO>
    loadSignalStates() {
        return signalStateDao == null ? List.of() : signalStateDao.selectAll();
    }
}
