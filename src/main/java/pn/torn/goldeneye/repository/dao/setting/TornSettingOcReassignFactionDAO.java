package pn.torn.goldeneye.repository.dao.setting;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.setting.TornSettingOcReassignFactionMapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignFactionDO;

/**
 * 帮派级大锅饭开关配置持久层
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@Repository
public class TornSettingOcReassignFactionDAO
        extends ServiceImpl<TornSettingOcReassignFactionMapper, TornSettingOcReassignFactionDO> {
}
