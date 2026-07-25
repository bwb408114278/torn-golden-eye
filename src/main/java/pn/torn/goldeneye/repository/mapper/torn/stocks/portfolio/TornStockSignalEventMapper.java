package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票信号事件数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockSignalEventMapper extends BaseMapper<TornStockSignalEventDO> {

    /**
     * 按轮次时间批量查询全部信号事件
     *
     * @param roundTime 轮次时间
     * @return 该轮次的全部信号事件列表
     */
    List<TornStockSignalEventDO> selectByRoundTime(@Param("roundTime") LocalDateTime roundTime);
}
