package pn.torn.goldeneye.repository.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.user.TornUserStockBlockDO;

/**
 * Torn用户股票屏蔽数据库访问层
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@Mapper
public interface TornUserStockBlockMapper extends BaseMapper<TornUserStockBlockDO> {
}
