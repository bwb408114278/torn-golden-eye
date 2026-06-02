package pn.torn.goldeneye.torn.manager.torn;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.torn.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.StockStrategyFeatureUpsert;
import pn.torn.goldeneye.torn.model.torn.stocks.StockRollingState;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票回溯特征值引擎
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.06.02
 */
@Component
public class StockRollingFeatureEngine {
    private final Map<Integer, StockRollingState> stateMap = new HashMap<>();

    public synchronized List<StockStrategyFeatureUpsert> addAndCalculate(List<StockPricePoint> points) {
        return points.stream()
                .sorted(Comparator.comparing(StockPricePoint::time))
                .map(this::addSinglePoint)
                .toList();
    }

    private StockStrategyFeatureUpsert addSinglePoint(StockPricePoint point) {
        StockRollingState state = stateMap.computeIfAbsent(point.stocksId(), key -> new StockRollingState());
        return state.addAndCalculate(point);
    }
}