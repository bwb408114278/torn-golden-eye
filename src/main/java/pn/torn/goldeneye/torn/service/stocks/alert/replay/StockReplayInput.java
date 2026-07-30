package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.util.Comparator;
import java.util.List;

/**
 * 回放只读输入快照。
 *
 * @param bars bar事实
 * @param features 特征事实
 * @param monthlyStates 已确认月度状态
 * @param signalStates 信号状态
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public record StockReplayInput(
        List<TornStockMarketBar15mDO> bars,
        List<TornStockStrategyFeature15mDO> features,
        List<TornStockMonthlyStateDO> monthlyStates,
        List<TornStockSignalStateDO> signalStates
) {
    public StockReplayInput {
        bars = (bars == null ? List.<TornStockMarketBar15mDO>of() : bars).stream()
                .sorted(Comparator.comparing(TornStockMarketBar15mDO::getStocksId,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TornStockMarketBar15mDO::getBarStartTime,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        features = (features == null ? List.<TornStockStrategyFeature15mDO>of() : features).stream()
                .sorted(Comparator.comparing(TornStockStrategyFeature15mDO::getStocksId,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TornStockStrategyFeature15mDO::getBarStartTime,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        monthlyStates = List.copyOf(monthlyStates == null ? List.of() : monthlyStates);
        signalStates = List.copyOf(signalStates == null ? List.of() : signalStates);
    }

    public StockReplayInput(List<TornStockMarketBar15mDO> bars,
                            List<TornStockStrategyFeature15mDO> features) {
        this(bars, features, List.of(), List.of());
    }
}
