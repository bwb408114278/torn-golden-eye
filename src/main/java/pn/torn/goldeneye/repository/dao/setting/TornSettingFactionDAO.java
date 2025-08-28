package pn.torn.goldeneye.repository.dao.setting;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.setting.TornSettingFactionMapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;

/**
 * Torn设置帮派持久层类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.28
 */
@Repository
public class TornSettingFactionDAO extends ServiceImpl<TornSettingFactionMapper, TornSettingFactionDO> {
}