package pn.torn.goldeneye.torn.manager.setting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;

/**
 * 系统设置公共逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.09.17
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SysSettingManager implements DataCacheManager {
    private final SysSettingDAO settingDao;

    @Override
    @CacheEvict(value = CacheConstants.KEY_SYS_SETTING, allEntries = true)
    public void refreshCache() {
        log.info("系统设置缓存已重置");
    }

    /**
     * 更新数据并清除缓存
     */
    @CacheEvict(cacheNames = CacheConstants.KEY_SYS_SETTING, key = "#settingKey")
    public void updateSetting(String settingKey, String settingValue) {
        settingDao.updateSetting(settingKey, settingValue);
    }

    /**
     * 获取配置值
     *
     * @param settingKey 配置Key
     * @return 管理员QQ号列表
     */
    @Cacheable(value = CacheConstants.KEY_SYS_SETTING, key = "#settingKey")
    public String getSettingValue(String settingKey) {
        return settingDao.querySettingValue(settingKey);
    }
}