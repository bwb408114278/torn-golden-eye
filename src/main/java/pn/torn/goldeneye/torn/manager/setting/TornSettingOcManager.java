package pn.torn.goldeneye.torn.manager.setting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;

import java.util.List;

/**
 * OC设置公共逻辑层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.17
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TornSettingOcManager implements DataCacheManager {
    private final TornSettingOcDAO settingOcDao;

    @Override
    @CacheEvict(value = CacheConstants.KEY_TORN_SETTING_OC, allEntries = true)
    public void refreshCache() {
        log.info("OC设置缓存已重置");
    }

    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_OC)
    public List<TornSettingOcDO> getList() {
        return settingOcDao.list();
    }
}