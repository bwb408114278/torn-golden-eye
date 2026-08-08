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

    /**
     * 按round_time查询轮次并加行锁(FOR UPDATE),用于事务内锁定
     *
     * @param roundTime 轮次时间
     * @return 锁定的轮次记录,无则返回null
     */
    TornStockMarketRoundDO selectByRoundTimeForUpdate(@Param("roundTime") LocalDateTime roundTime);

    /**
     * 幂等插入最近已结束桶的PENDING轮次。
     * <p>
     * 使用数据库部分唯一索引 {@code uk_stock_market_round_time} 的同一语义
     * {@code (round_time) WHERE deleted = 0} 执行 {@code INSERT ... ON CONFLICT DO NOTHING},
     * 作为定时入口与启动补偿双入口/重启重试的幂等兜底;返回实际插入行数,冲突行被忽略不影响幂等。
     *
     * @param round 待插入的PENDING轮次(须填充roundTime与全部规则版本字段)
     * @return 实际插入行数(0表示已存在同round_time有效轮次)
     */
    int insertPendingRoundIgnoreConflict(@Param("round") TornStockMarketRoundDO round);
}
