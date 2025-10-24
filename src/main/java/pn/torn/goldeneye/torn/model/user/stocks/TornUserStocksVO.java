package pn.torn.goldeneye.torn.model.user.stocks;

import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Torn用户股票响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.27
 */
@Data
public class TornUserStocksVO {
    /**
     * 已购股票列表，Key为股票ID
     */
    private Map<String, TornUserStocksDetailVO> stocks;

    @JsonSetter("stocks")
    public void setStocks(Object stocks) {
        if (stocks instanceof Map) {
            this.stocks = (Map<String, TornUserStocksDetailVO>) stocks;
        } else if (stocks instanceof Collection) {
            this.stocks = new HashMap<>();
        } else {
            this.stocks = new HashMap<>();
        }
    }
}