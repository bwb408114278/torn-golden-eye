package pn.torn.goldeneye.torn.manager.setting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionOcPlanDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionOcPlanningPolicyDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcChainDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcPlanProfileDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanningPolicyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;

import java.util.List;

/**
 * OC新队规划配置管理器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornSettingOcPlanningManager implements DataCacheManager {
    private static final String PROFILE_CACHE = "torn:setting:oc:planning:profile";
    private static final String CHAIN_CACHE = "torn:setting:oc:planning:chain";
    private static final String FACTION_PLAN_CACHE = "torn:setting:oc:planning:faction";
    private static final String POLICY_CACHE = "torn:setting:oc:planning:policy";

    private final TornSettingOcPlanProfileDAO profileDao;
    private final TornSettingOcChainDAO chainDao;
    private final TornSettingFactionOcPlanDAO factionPlanDao;
    private final TornSettingFactionOcPlanningPolicyDAO policyDao;

    @Override
    public void warmUpCache() {
        getProfiles();
        getChains();
        getFactionPlans();
        getPolicies();
    }

    @Override
    @CacheEvict(cacheNames = {PROFILE_CACHE, CHAIN_CACHE, FACTION_PLAN_CACHE, POLICY_CACHE},
            allEntries = true)
    public void refreshCache() {
        log.info("OC新队规划配置缓存已重置");
    }

    @Cacheable(PROFILE_CACHE)
    public List<TornSettingOcPlanProfileDO> getProfiles() {
        return profileDao.lambdaQuery().eq(TornSettingOcPlanProfileDO::getDeleted, 0).list();
    }

    @Cacheable(CHAIN_CACHE)
    public List<TornSettingOcChainDO> getChains() {
        return chainDao.lambdaQuery()
                .eq(TornSettingOcChainDO::getDeleted, 0)
                .eq(TornSettingOcChainDO::getEnabled, true)
                .orderByAsc(TornSettingOcChainDO::getChainCode)
                .orderByAsc(TornSettingOcChainDO::getSequenceNo)
                .list();
    }

    @Cacheable(FACTION_PLAN_CACHE)
    public List<TornSettingFactionOcPlanDO> getFactionPlans() {
        return factionPlanDao.lambdaQuery()
                .eq(TornSettingFactionOcPlanDO::getDeleted, 0)
                .eq(TornSettingFactionOcPlanDO::getEnabled, true)
                .list();
    }

    @Cacheable(POLICY_CACHE)
    public List<TornSettingFactionOcPlanningPolicyDO> getPolicies() {
        return policyDao.lambdaQuery()
                .eq(TornSettingFactionOcPlanningPolicyDO::getDeleted, 0)
                .list();
    }
}
