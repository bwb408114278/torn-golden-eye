package pn.torn.goldeneye.repository.mapper.faction.armory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;

/**
 * 帮派物品使用记录数据库访问层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@Mapper
public interface TornFactionItemUsedMapper extends BaseMapper<TornFactionItemUsedDO> {
}