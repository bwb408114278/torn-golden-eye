package pn.torn.goldeneye.torn.manager.setting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;

import java.util.Arrays;
import java.util.List;

/**
 * 系统设置公共逻辑层
 *
 * @author Bai
 * @version 0.2.0
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

    /**
     * 获取系统管理员列表
     *
     * @return 管理员QQ号列表
     */
    @Cacheable(value = CacheConstants.KEY_SYS_SETTING,
            key = "T(pn.torn.goldeneye.constants.torn.SettingConstants).KEY_ADMIN")
    public List<Long> getSysAdmin() {
        String adminIdStr = settingDao.querySettingValue(SettingConstants.KEY_ADMIN);
        return Arrays.stream(adminIdStr.split(",")).map(Long::parseLong).toList();
    }

    /**
     * 获取是否屏蔽聊天
     */
    @Cacheable(value = CacheConstants.KEY_SYS_SETTING,
            key = "T(pn.torn.goldeneye.constants.torn.SettingConstants).KEY_BLOCK_CHAT")
    public boolean getIsBlockChat() {
        String isBlockStr = settingDao.querySettingValue(SettingConstants.KEY_BLOCK_CHAT);
        return Boolean.parseBoolean(isBlockStr);
    }
}