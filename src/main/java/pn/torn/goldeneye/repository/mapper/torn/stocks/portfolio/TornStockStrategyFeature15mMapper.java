package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票15分钟bar策略特征数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockStrategyFeature15mMapper extends BaseMapper<TornStockStrategyFeature15mDO> {

    /**
     * 按bar开始时间批量查询全部股票特征
     *
     * @param barStartTime bar开始时间
     * @return 该时间点的全部股票特征列表
     */
    List<TornStockStrategyFeature15mDO> selectByBarStartTime(@Param("barStartTime") LocalDateTime barStartTime);

    /**
     * 查询指定股票在指定时间及之前的最新特征
     *
     * @param stocksIds       股票ID列表
     * @param maxBarStartTime 最大bar开始时间(含)
     * @return 按股票ID与bar时间倒序排列的特征列表
     */
    List<TornStockStrategyFeature15mDO> selectLatestByStocksIds(@Param("stocksIds") List<Integer> stocksIds,
                                                                @Param("maxBarStartTime") LocalDateTime maxBarStartTime);
}
