package pn.torn.goldeneye.repository.mapper.faction.funds;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.faction.funds.TornFactionGiveFundsDO;

/**
 * 帮派取钱记录数据库访问层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2026.01.12
 */
@Mapper
public interface TornFactionGiveFundsMapper extends BaseMapper<TornFactionGiveFundsDO> {
}