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

    /**
     * 按时间范围批量查询全部股票bar,避免逐桶查询产生N+1问题
     *
     * @param startTime    起始时间(含)
     * @param endTime      结束时间(含)
     * @param buildVersion bar构建版本
     * @return 按股票ID和bar时间升序排列的bar列表
     */
    List<TornStockMarketBar15mDO> selectByTimeRange(@Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime,
                                                    @Param("buildVersion") String buildVersion);

    /**
     * 按唯一键(stocks_id, bar_start_time, build_version)执行UPSERT
     *
     * @param bar 待插入或更新的bar
     * @return 影响行数
     */
    int upsertBar(@Param("bar") TornStockMarketBar15mDO bar);
}
