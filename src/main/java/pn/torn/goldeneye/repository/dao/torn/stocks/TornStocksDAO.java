package pn.torn.goldeneye.repository.dao.torn.stocks;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.TornStocksMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;

/**
 * Torn股票持久层类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Repository
public class TornStocksDAO extends ServiceImpl<TornStocksMapper, TornStocksDO> {
}