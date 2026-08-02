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

    /**
     * 批量查询未结算拒绝观察事件。
     *
     * @param startTime 轮次起点(含)
     * @param endTime   轮次终点(不含)
     * @return 未结算拒绝观察事件
     */
    List<TornStockSignalEventDO> selectPendingRejectedObservationEvents(
            @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 批量查询全部未结算拒绝观察事件,用于停机补偿。
     *
     * @return 未结算拒绝观察事件
     */
    List<TornStockSignalEventDO> selectAllPendingRejectedObservationEvents();

    /**
     * 批量回写拒绝观察结果,只更新尚未结算的事件。
     *
     * @param events 拒绝观察结果列表
     * @return 实际更新行数
     */
    int updateObservationResultsByIds(@Param("events") List<TornStockSignalEventDO> events);

    /**
     * 判断是否存在未结算的拒绝观察事件。
     *
     * @return 存在未结算拒绝观察事件返回true;否则false
     */
    boolean existsPendingRejectedObservationEvents();
}