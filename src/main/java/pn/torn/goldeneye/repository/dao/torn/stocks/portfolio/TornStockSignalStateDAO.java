package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockSignalStateMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;

import java.util.List;

/**
 * Torn股票信号状态持久层类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Repository
public class TornStockSignalStateDAO extends ServiceImpl<TornStockSignalStateMapper, TornStockSignalStateDO> {

    /**
     * 查询全部信号状态,批量获取避免N+1
     *
     * @return 全部信号状态列表
     */
    public List<TornStockSignalStateDO> selectAll() {
        return baseMapper.selectAll();
    }

    /**
     * 按股票ID列表和买入规则版本批量查询信号状态,避免逐股查询产生N+1问题
     *
     * @param stocksIds      股票ID列表
     * @param buyRuleVersion 买入规则版本
     * @return 符合条件的信号状态列表
     */
    public List<TornStockSignalStateDO> selectByStocksIdsAndVersion(List<Integer> stocksIds, String buyRuleVersion) {
        return baseMapper.selectByStocksIdsAndVersion(stocksIds, buyRuleVersion);
    }
}
