package pn.torn.goldeneye.torn.manager.setting;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionOcDisableDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcDisableDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcSlotDO;

import java.util.List;

/**
 * 帮派Oc设置公共逻辑层
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.04.27
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TornSettingFactionOcManager implements DataCacheManager {
    private final TornSettingFactionOcDisableDAO factionOcDisableDao;
    private final TornSettingFactionOcSlotDAO factionOcSlotDao;
    @Lazy
    @Resource
    private TornSettingFactionOcManager factionOcManager;

    @Override
    public void warmUpCache() {
        factionOcManager.getDisableList();
        factionOcManager.getSlotList();
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_SETTING_FACTION_OC_DISABLE, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_SETTING_FACTION_OC_SLOT, allEntries = true)})
    public void refreshCache() {
        log.info("帮派OC设置缓存已重置");
    }

    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_FACTION_OC_DISABLE)
    public List<TornSettingFactionOcDisableDO> getDisableList() {
        return factionOcDisableDao.list();
    }

    @Cacheable(value = CacheConstants.KEY_TORN_SETTING_FACTION_OC_SLOT)
    public List<TornSettingFactionOcSlotDO> getSlotList() {
        return factionOcSlotDao.list();
    }

    /**
     * 该帮派是否禁用此OC
     */
    public boolean isOcDisabled(long factionId, TornFactionOcDO oc) {
        return factionOcManager.getDisableList().stream()
                .anyMatch(c -> c.getFactionId().equals(factionId)
                        && c.getOcName().equals(oc.getName())
                        && c.getRank().equals(oc.getRank()));
    }

    /**
     * 获取帮派对某岗位的自定义配置，返回null表示无覆盖
     */
    public TornSettingFactionOcSlotDO getFactionSlot(long factionId, TornFactionOcDO oc, String slotCode) {
        return factionOcManager.getSlotList().stream()
                .filter(c -> c.getFactionId().equals(factionId)
                        && c.getOcName().equals(oc.getName())
                        && c.getRank().equals(oc.getRank())
                        && slotCode.equals(c.getSlotCode()))
                .findFirst().orElse(null);
    }
}