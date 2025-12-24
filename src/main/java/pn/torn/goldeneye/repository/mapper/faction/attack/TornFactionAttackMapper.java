package pn.torn.goldeneye.repository.mapper.faction.attack;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionAttackDO;

/**
 * 帮派战斗记录数据库访问层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Mapper
public interface TornFactionAttackMapper extends BaseMapper<TornFactionAttackDO> {
}