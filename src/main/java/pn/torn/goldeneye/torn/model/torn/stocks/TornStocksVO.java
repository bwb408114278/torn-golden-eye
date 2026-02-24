package pn.torn.goldeneye.torn.model.torn.stocks;

import lombok.Data;

import java.util.List;

/**
 * Torn股票响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.09.26
 */
@Data
public class TornStocksVO {
    /**
     * 股票详情列表
     */
    private List<TornStocksDetailVO> stocks;
}