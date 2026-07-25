package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票15分钟K线(bar)数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockMarketBar15mMapper extends BaseMapper<TornStockMarketBar15mDO> {

    /**
     * 按bar开始时间批量查询全部股票bar
     *
     * @param barStartTime bar开始时间
     * @return 该时间点的全部股票bar列表
     */
    List<TornStockMarketBar15mDO> selectByBarStartTime(@Param("barStartTime") LocalDateTime barStartTime);
}
