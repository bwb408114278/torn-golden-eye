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
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornSettingOcPlanningManager implements DataCacheManager {
    private final TornSettingOcPlanProfileDAO profileDao;
    private final TornSettingOcChainDAO chainDao;
    private final TornSettingFactionOcPlanDAO factionPlanDao;
    private final TornSettingFactionOcPlanningPolicyDAO policyDao;
    @Lazy
    @Resource
    private TornSettingOcPlanningManager planningManager;

    @Override
    public void warmUpCache() {
        planningManager.getProfiles();
        planningManager.getChains();
        planningManager.getFactionPlans();
        planningManager.getPolicies();
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.KEY_OC_TEAM_PLAN_PROFILE, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_OC_TEAM_PLAN_CHAIN, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_OC_TEAM_PLAN_FACTION_PLAN, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_OC_TEAM_PLAN_POLICY, allEntries = true)})
    public void refreshCache() {
        log.info("OC新队规划配置缓存已重置");
    }


    @Cacheable(value = CacheConstants.KEY_OC_TEAM_PLAN_PROFILE)
    public List<TornSettingOcPlanProfileDO> getProfiles() {
        return profileDao.lambdaQuery().eq(TornSettingOcPlanProfileDO::getDeleted, 0).list();
    }

    /**
     * 查询已启用且未删除的OC高阶链配置。
     *
     * @return 按链编码和节点顺序排序的高阶链配置
     */
    @Cacheable(value = CacheConstants.KEY_OC_TEAM_PLAN_CHAIN)
    public List<TornSettingOcChainDO> getChains() {
        return chainDao.lambdaQuery()
                .eq(TornSettingOcChainDO::getDeleted, 0)
                .eq(TornSettingOcChainDO::getEnabled, true)
                .orderByAsc(TornSettingOcChainDO::getChainCode)
                .orderByAsc(TornSettingOcChainDO::getSequenceNo)
                .list();
    }

    /**
     * 查询已启用且未删除的帮派OC规划范围。
     *
     * @return 帮派OC规划范围配置
     */
    @Cacheable(value = CacheConstants.KEY_OC_TEAM_PLAN_FACTION_PLAN)
    public List<TornSettingFactionOcPlanDO> getFactionPlans() {
        return factionPlanDao.lambdaQuery()
                .eq(TornSettingFactionOcPlanDO::getDeleted, 0)
                .eq(TornSettingFactionOcPlanDO::getEnabled, true)
                .list();
    }

    /**
     * 查询未删除的帮派OC规划策略。
     *
     * @return 帮派OC规划策略配置
     */
    @Cacheable(value = CacheConstants.KEY_OC_TEAM_PLAN_POLICY)
    public List<TornSettingFactionOcPlanningPolicyDO> getPolicies() {
        return policyDao.lambdaQuery()
                .eq(TornSettingFactionOcPlanningPolicyDO::getDeleted, 0)
                .list();
    }
}
