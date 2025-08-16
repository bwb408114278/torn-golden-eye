package pn.torn.goldeneye.repository.dao.setting;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.setting.SysSettingMapper;
import pn.torn.goldeneye.repository.model.setting.SysSettingDO;

/**
 * 系统设置持久层类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.08
 */
@Repository
public class SysSettingDAO extends ServiceImpl<SysSettingMapper, SysSettingDO> {
    /**
     * 更新配置
     */
    public void updateSetting(String key, String value) {
        String exists = querySettingValue(key);
        if (exists != null) {
            lambdaUpdate().set(SysSettingDO::getSettingValue, value).eq(SysSettingDO::getSettingKey, key).update();
        } else {
            save(new SysSettingDO(key, value));
        }
    }

    /**
     * 获取配置的值
     */
    public String querySettingValue(String key) {
        SysSettingDO setting = lambdaQuery().eq(SysSettingDO::getSettingKey, key).one();
        return setting == null ? null : setting.getSettingValue();
    }
}