package pn.torn.goldeneye.repository.dao.torn.stocks;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.TornStocksMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;

import java.util.List;

/**
 * Torn股票持久层类
 *
 * @author Bai
 * @version 1.9.3
 * @since 2025.09.26
 */
@Repository
public class TornStocksDAO extends ServiceImpl<TornStocksMapper, TornStocksDO> {
    /**
     * 查询有分红收益的股票(分红指令候选池)
     *
     * @return 分红收益大于0的股票列表
     */
    public List<TornStocksDO> selectDividendStocks() {
        return lambdaQuery().gt(TornStocksDO::getProfit, 0).list();
    }
}