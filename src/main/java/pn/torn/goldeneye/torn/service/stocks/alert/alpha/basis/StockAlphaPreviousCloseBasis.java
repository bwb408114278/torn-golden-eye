package pn.torn.goldeneye.torn.service.stocks.alert.alpha.basis;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 现行口径(PREVIOUS_CLOSE) - 使用已结束自然日的23:45收盘序列,与现行生产决策完全一致。
 * <p>
 * 本实现是生产的唯一下单依据口径,不新增任何计算,只复用
 * {@link StockAlphaPriceBasis.StockAlphaBasisInput#previousCloseSeries()}。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.18
 */
public class StockAlphaPreviousCloseBasis implements StockAlphaPriceBasis {
    @Override
    public String code() {
        return "PREVIOUS_CLOSE";
    }

    @Override
    public Map<Integer, List<BigDecimal>> closeSeries(StockAlphaBasisInput input) {
        return input == null ? null : input.previousCloseSeries();
    }
}
