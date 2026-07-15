package pn.torn.goldeneye.repository.dao.setting;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.setting.TornSettingFactionOcPlanningPolicyMapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanningPolicyDO;

@Repository
public class TornSettingFactionOcPlanningPolicyDAO
        extends ServiceImpl<TornSettingFactionOcPlanningPolicyMapper,
        TornSettingFactionOcPlanningPolicyDO> {
}
