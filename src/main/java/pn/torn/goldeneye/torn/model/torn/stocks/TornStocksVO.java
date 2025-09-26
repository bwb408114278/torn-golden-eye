package pn.torn.goldeneye.torn.model.torn.stocks;

import lombok.Data;

import java.util.Map;

/**
 * Torn股票响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Data
public class TornStocksVO {
    /**
     * 股票详情, Key为股票ID
     */
    private Map<Integer, TornStocksDetailVO> stocks;
}