package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockBatchMarkDO;

import java.util.List;

/**
 * Torn股票批次标记数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockBatchMarkMapper extends BaseMapper<TornStockBatchMarkDO> {

    /**
     * 按批次ID批量查询全部mark
     *
     * @param batchId 批次ID
     * @return 该批次的全部标记列表(按轮次时间升序)
     */
    List<TornStockBatchMarkDO> selectByBatchId(@Param("batchId") Long batchId);
}
