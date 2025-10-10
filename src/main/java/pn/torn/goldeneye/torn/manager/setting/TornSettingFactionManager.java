package pn.torn.goldeneye.torn.manager.setting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;

import java.util.List;

/**
 * 帮派设置公共逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.10
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TornSettingFactionManager implements DataCacheManager {
    private final TornSettingFactionDAO settingFactionDao;

    @Override
    @CacheEvict(value = CacheConstants.KEY_TORN_SETTING_FACTION, allEntries = true)
    public void refreshCache() {
        log.info("帮派设置缓存已重置");
    }

    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_FACTION)
    public List<TornSettingFactionDO> getList() {
        return settingFactionDao.list();
    }
}