package pn.torn.goldeneye.torn.manager.setting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;

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
    @Caching(evict = {@CacheEvict(value = CacheConstants.KEY_SYS_SETTING, allEntries = true)})
    public void refreshCache() {
        log.info("系统设置缓存已重置");
    }

    @Cacheable(value = CacheConstants.KEY_SYS_SETTING, key = "#key")
    public String getCachedData(String key) {
        return settingDao.querySettingValue(key);
    }
}