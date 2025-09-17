package pn.torn.goldeneye.torn.manager.setting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcSlotDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;

import java.util.List;

/**
 * OC岗位设置公共逻辑层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.17
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TornSettingOcSlotManager implements DataCacheManager {
    private final TornSettingOcSlotDAO settingOcSlotDao;

    @Override
    @CacheEvict(value = CacheConstants.KEY_TORN_SETTING_OC_SLOT, allEntries = true)
    public void refreshCache() {
        log.info("OC设置缓存已重置");
    }

    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_OC_SLOT)
    public List<TornSettingOcSlotDO> getList() {
        return settingOcSlotDao.list();
    }
}