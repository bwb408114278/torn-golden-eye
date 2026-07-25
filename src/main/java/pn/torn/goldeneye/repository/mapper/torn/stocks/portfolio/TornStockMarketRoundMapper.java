package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票策略轮次记录数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockMarketRoundMapper extends BaseMapper<TornStockMarketRoundDO> {

    /**
     * 查询最后一个已完成的轮次
     *
     * @return 最近一个已完成轮次,无则返回null
     */
    TornStockMarketRoundDO selectLastCompleted();

    /**
     * 查询指定时间前未完成的轮次
     *
     * @param maxRoundTime 最大轮次时间(不含)
     * @return 未完成轮次列表(按轮次时间升序)
     */
    List<TornStockMarketRoundDO> selectPendingRoundsBefore(@Param("maxRoundTime") LocalDateTime maxRoundTime);
}
