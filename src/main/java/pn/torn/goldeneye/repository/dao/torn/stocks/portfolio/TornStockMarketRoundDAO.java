package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockMarketRoundMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票策略轮次记录持久层类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Repository
public class TornStockMarketRoundDAO extends ServiceImpl<TornStockMarketRoundMapper, TornStockMarketRoundDO> {

    /**
     * 查询最后一个已完成的轮次
     *
     * @return 最近一个已完成轮次,无则返回null
     */
    public TornStockMarketRoundDO selectLastCompleted() {
        return baseMapper.selectLastCompleted();
    }

    /**
     * 查询指定时间前未完成的轮次,批量获取避免N+1
     *
     * @param maxRoundTime 最大轮次时间(不含)
     * @return 未完成轮次列表(按轮次时间升序)
     */
    public List<TornStockMarketRoundDO> selectPendingRoundsBefore(LocalDateTime maxRoundTime) {
        return baseMapper.selectPendingRoundsBefore(maxRoundTime);
    }

    /**
     * 按round_time查询轮次并加行锁(FOR UPDATE),用于事务内锁定
     *
     * @param roundTime 轮次时间
     * @return 锁定的轮次记录,无则返回null
     */
    public TornStockMarketRoundDO selectByRoundTimeForUpdate(LocalDateTime roundTime) {
        return baseMapper.selectByRoundTimeForUpdate(roundTime);
    }
}
