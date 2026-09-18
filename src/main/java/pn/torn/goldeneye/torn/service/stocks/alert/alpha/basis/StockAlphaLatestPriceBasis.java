package pn.torn.goldeneye.torn.service.stocks.alert.alpha.basis;

import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution.StockAlphaExecutionBarPolicy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 观察口径(LATEST_PRICE) - 把决策桶(生产为08:00桶)现价当作新增一天追加到序列末尾。
 * <p>
 * 追加一天后,r1 = 现价 ÷ 前一日23:45收盘,r20 = 现价 ÷ 前第20个共同有效日,
 * 回溯窗口整体平移一天。本实现只在{LATEST_PRICE}口径内使用决策bar价格,
 * 不污染{PREVIOUS_CLOSE}序列,也不写入日线快照表。
 * <p>
 * 任一股票池成员在本轮缺少正价决策bar时,序列无法构成完整横截面,本实现返回空映射,
 * 由决策服务把观察列写空,绝不阻断生产决策。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.18
 */
public class StockAlphaLatestPriceBasis implements StockAlphaPriceBasis {
    @Override
    public String code() {
        return "LATEST_PRICE";
    }

    @Override
    public Map<Integer, List<BigDecimal>> closeSeries(StockAlphaBasisInput input) {
        if (input == null || input.decisionBars() == null || input.decisionBars().isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<BigDecimal>> closes = input.previousCloseSeries();
        if (closes.size() != StockAlphaRuleDefinition.MEMBER_COUNT) {
            return Map.of();
        }
        for (Integer stocksId : StockAlphaRuleDefinition.stockUniverse()) {
            BigDecimal price = latestPrice(input.decisionBars().get(stocksId));
            List<BigDecimal> series = closes.get(stocksId);
            if (price == null || series == null) {
                return Map.of();
            }
            series.add(price);
        }
        return closes;
    }

    /**
     * 取决策bar的现价。
     *
     * @param bar 决策时点该股票的决策bar事实;缺失时为null
     * @return 价格为正的决策bar最后价;缺失或价格非正时返回null
     */
    private BigDecimal latestPrice(StockAlphaExecutionBarPolicy.DecisionBar bar) {
        if (bar == null || bar.price() == null || bar.price().signum() <= 0) {
            return null;
        }
        return bar.price();
    }
}
