package pn.torn.goldeneye.torn.service.faction.oc.recommend.replay;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignFactionDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignOcDAO;
import pn.torn.goldeneye.torn.manager.faction.crime.recommend.TornOcRecommendManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcPlanningManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcRecommendService;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.MappedOcYieldEstimator;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.MeasuredOcYieldEstimator;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.OcYieldEstimator;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.OcYieldEstimatorRegistry;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcOpportunityCostRanker;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcScarcityEstimator;

import java.util.List;

/**
 * OC推荐只读回放的补充测试上下文：复用OC规划回放的DataSource/MyBatis/DAO骨架，
 * 只追加推荐链路自身需要的DAO、度量与排序组件，不启动GoldenEyeApplication。
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@TestConfiguration
@EnableCaching(proxyTargetClass = true)
public class OcRecommendReplayTestConfiguration {

    /**
     * 构造回放用缓存管理器，等价生产侧的配置缓存语义（配置列表只加载一次）。
     *
     * @return 内存缓存管理器
     */
    @Bean
    CacheManager cacheManager() {
        return new ConcurrentMapCacheManager();
    }

    @Bean
    TornFactionOcIncomeDAO tornFactionOcIncomeDAO() {
        return new TornFactionOcIncomeDAO();
    }

    @Bean
    TornSettingOcReassignFactionDAO tornSettingOcReassignFactionDAO() {
        return new TornSettingOcReassignFactionDAO();
    }

    @Bean
    TornSettingOcReassignOcDAO tornSettingOcReassignOcDAO() {
        return new TornSettingOcReassignOcDAO();
    }

    @Bean
    TornOcRecommendManager tornOcRecommendManager(TornSettingFactionOcManager factionOcManager,
                                                  TornSettingOcSlotManager ocSlotManager) {
        return new TornOcRecommendManager(factionOcManager, ocSlotManager);
    }

    @Bean
    MeasuredOcYieldEstimator measuredOcYieldEstimator(TornFactionOcIncomeDAO incomeDAO,
                                                      TornSettingOcManager ocManager) {
        return new MeasuredOcYieldEstimator(incomeDAO, ocManager);
    }

    @Bean
    MappedOcYieldEstimator mappedOcYieldEstimator(TornSettingOcManager ocManager,
                                                  TornSettingOcPlanningManager planningManager) {
        return new MappedOcYieldEstimator(ocManager, planningManager);
    }

    @Bean
    OcYieldEstimatorRegistry ocYieldEstimatorRegistry(MeasuredOcYieldEstimator measured,
                                                      MappedOcYieldEstimator mapped) {
        List<OcYieldEstimator> estimators = List.of(measured, mapped);
        return new OcYieldEstimatorRegistry(estimators, measured, mapped);
    }

    @Bean
    OcScarcityEstimator ocScarcityEstimator() {
        return new OcScarcityEstimator();
    }

    @Bean
    OcOpportunityCostRanker ocOpportunityCostRanker(OcScarcityEstimator scarcityEstimator) {
        return new OcOpportunityCostRanker(scarcityEstimator);
    }

    @Bean
    TornOcRecommendService tornOcRecommendService(TornOcRecommendManager recommendManager,
                                                  OcYieldEstimatorRegistry registry,
                                                  OcOpportunityCostRanker ranker,
                                                  TornFactionOcDAO ocDAO,
                                                  TornFactionOcSlotDAO slotDAO,
                                                  TornFactionOcUserDAO ocUserDAO) {
        return new TornOcRecommendService(recommendManager, registry, ranker, ocDAO, slotDAO, ocUserDAO);
    }
}
