package pn.torn.goldeneye.repository.mapper.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanningPolicyDO;

/**
 * 帮派OC规划策略配置数据访问映射器。
 *
 * <p>负责映射 TornSettingFactionOcPlanningPolicyDO 与对应配置表的基础CRUD操作。</p>
 */
@Mapper
public interface TornSettingFactionOcPlanningPolicyMapper
        extends BaseMapper<TornSettingFactionOcPlanningPolicyDO> {
}
