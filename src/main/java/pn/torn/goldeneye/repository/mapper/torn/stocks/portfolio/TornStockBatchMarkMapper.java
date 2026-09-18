package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockBatchMarkDO;

/**
 * Torn股票批次标记数据库访问层
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.07.24
 */
@Mapper
public interface TornStockBatchMarkMapper extends BaseMapper<TornStockBatchMarkDO> {
}
