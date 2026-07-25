package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票虚拟交易批次数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockVirtualBatchMapper extends BaseMapper<TornStockVirtualBatchDO> {

    /**
     * 查询全部正式活跃批次
     *
     * @return 正式活跃批次列表
     */
    List<TornStockVirtualBatchDO> selectActiveFormalBatches();

    /**
     * 查询待买入批次(预期入场bar时间已到期)
     *
     * @param currentTime 当前时间
     * @return 待买入批次列表
     */
    List<TornStockVirtualBatchDO> selectPendingEntryBatches(@Param("currentTime") LocalDateTime currentTime);

    /**
     * 查询待卖出批次(预期平仓bar时间已到期)
     *
     * @param currentTime 当前时间
     * @return 待卖出批次列表
     */
    List<TornStockVirtualBatchDO> selectPendingExitBatches(@Param("currentTime") LocalDateTime currentTime);
}
