package pn.torn.goldeneye.torn.model.user.stocks;

import lombok.Data;

import java.util.List;

/**
 * Torn用户股票响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.09.27
 */
@Data
public class TornUserStocksVO {
    /**
     * 已购股票列表
     */
    private List<TornUserStocksDetailVO> stocks;
}