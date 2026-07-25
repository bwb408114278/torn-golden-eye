package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockSignalEventMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票信号事件持久层类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Repository
public class TornStockSignalEventDAO extends ServiceImpl<TornStockSignalEventMapper, TornStockSignalEventDO> {

    /**
     * 按轮次时间批量查询全部信号事件,避免逐股查询产生N+1问题
     *
     * @param roundTime 轮次时间
     * @return 该轮次的全部信号事件列表
     */
    public List<TornStockSignalEventDO> selectByRoundTime(LocalDateTime roundTime) {
        return baseMapper.selectByRoundTime(roundTime);
    }
}
