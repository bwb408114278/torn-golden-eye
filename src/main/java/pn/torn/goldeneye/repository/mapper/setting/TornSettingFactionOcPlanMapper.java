package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanDO;

/**
 * 帮派OC规划范围配置数据访问映射器。
 *
 * <p>负责映射 TornSettingFactionOcPlanDO 与对应配置表的基础CRUD操作。</p>
 */
@Mapper
public interface TornSettingFactionOcPlanMapper extends BaseMapper<TornSettingFactionOcPlanDO> {
}
