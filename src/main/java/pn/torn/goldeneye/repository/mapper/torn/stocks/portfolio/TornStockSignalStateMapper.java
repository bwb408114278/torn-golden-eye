package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;

import java.util.List;

/**
 * Torn股票信号状态数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockSignalStateMapper extends BaseMapper<TornStockSignalStateDO> {

    /**
     * 查询全部信号状态
     *
     * @return 全部信号状态列表
     */
    List<TornStockSignalStateDO> selectAll();

    /**
     * 按股票ID列表和买入规则版本批量查询信号状态
     *
     * @param stocksIds      股票ID列表
     * @param buyRuleVersion 买入规则版本
     * @return 符合条件的信号状态列表
     */
    List<TornStockSignalStateDO> selectByStocksIdsAndVersion(@Param("stocksIds") List<Integer> stocksIds,
                                                             @Param("buyRuleVersion") String buyRuleVersion);
}
