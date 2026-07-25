package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockMarketBar15mMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票15分钟K线(bar)持久层类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Repository
public class TornStockMarketBar15mDAO extends ServiceImpl<TornStockMarketBar15mMapper, TornStockMarketBar15mDO> {

    /**
     * 按bar开始时间批量查询全部股票bar,避免逐股查询产生N+1问题
     *
     * @param barStartTime bar开始时间
     * @return 该时间点的全部股票bar列表
     */
    public List<TornStockMarketBar15mDO> selectByBarStartTime(LocalDateTime barStartTime) {
        return baseMapper.selectByBarStartTime(barStartTime);
    }
}
