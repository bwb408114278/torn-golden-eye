package pn.torn.goldeneye.torn.manager.setting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_SETTING_FACTION, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_SETTING_FACTION_GROUP_ID, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_SETTING_FACTION_ALIAS, allEntries = true)})
    public void refreshCache() {
        log.info("帮派设置缓存已重置");
    }

    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_FACTION)
    public List<TornSettingFactionDO> getList() {
        return settingFactionDao.list();
    }

    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_FACTION_GROUP_ID)
    public Map<Long, TornSettingFactionDO> getGroupIdMap() {
        List<TornSettingFactionDO> list = settingFactionDao.list();
        Map<Long, TornSettingFactionDO> resultMap = new HashMap<>();
        for (TornSettingFactionDO faction : list) {
            if (faction.getGroupId() > 0L) {
                resultMap.put(faction.getGroupId(), faction);
            }
        }

        return resultMap;
    }

    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_FACTION_ALIAS)
    public Map<String, TornSettingFactionDO> getAliasMap() {
        List<TornSettingFactionDO> list = settingFactionDao.list();
        Map<String, TornSettingFactionDO> resultMap = new HashMap<>();
        for (TornSettingFactionDO faction : list) {
            String[] aliasArray = faction.getFactionAlias().split(",");
            for (String alias : aliasArray) {
                resultMap.put(alias, faction);
            }
        }

        return resultMap;
    }
}