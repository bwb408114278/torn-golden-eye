package pn.torn.goldeneye.repository.mapper.faction.armory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionArmoryWarningDO;

/**
 * 帮派物资告警数据库访问层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.30
 */
@Mapper
public interface TornFactionArmoryWarningMapper extends BaseMapper<TornFactionArmoryWarningDO> {
}