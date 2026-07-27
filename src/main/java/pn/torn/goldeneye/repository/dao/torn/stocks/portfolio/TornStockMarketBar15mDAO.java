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
     * 按bar开始时间和构建版本批量查询全部股票bar,避免逐股查询产生N+1问题
     *
     * @param barStartTime bar开始时间
     * @param buildVersion bar构建版本
     * @return 该时间点指定版本的全部股票bar列表
     */
    public List<TornStockMarketBar15mDO> selectByBarStartTime(LocalDateTime barStartTime,
                                                               String buildVersion) {
        return baseMapper.selectByBarStartTime(barStartTime, buildVersion);
    }

    /**
     * 按时间范围批量查询全部股票bar,避免逐桶查询产生N+1问题
     *
     * @param startTime    起始时间(含)
     * @param endTime      结束时间(含)
     * @param buildVersion bar构建版本
     * @return 按股票ID和bar时间升序排列的bar列表
     */
    public List<TornStockMarketBar15mDO> selectByTimeRange(LocalDateTime startTime,
                                                           LocalDateTime endTime,
                                                           String buildVersion) {
        return baseMapper.selectByTimeRange(startTime, endTime, buildVersion);
    }

    /**
     * 按唯一键执行UPSERT,支持幂等重试
     *
     * @param bar 待插入或更新的bar
     */
    public void upsertBar(TornStockMarketBar15mDO bar) {
        baseMapper.upsertBar(bar);
    }
}
