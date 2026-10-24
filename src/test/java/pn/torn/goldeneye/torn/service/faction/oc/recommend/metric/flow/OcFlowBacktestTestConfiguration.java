package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcFlowEdgeDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcFlowNodeDAO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcFlowManager;

/**
 * OC流程图回测的补充测试上下文：复用OC规划回放的DataSource/MyBatis/DAO骨架，
 * 只追加流程图配置的DAO、装配Manager与求值器，不启动GoldenEyeApplication。
 *
 * <p>缓存管理器与生产侧同为内存缓存语义：流程图只在首次装配时做一次结构校验，
 * 回测期反复取图不会重复装配。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
@TestConfiguration
@EnableCaching(proxyTargetClass = true)
public class OcFlowBacktestTestConfiguration {

    /**
     * 构造回测用缓存管理器。
     *
     * @return 内存缓存管理器
     */
    @Bean
    CacheManager cacheManager() {
        return new ConcurrentMapCacheManager();
    }

    @Bean
    TornSettingOcFlowNodeDAO tornSettingOcFlowNodeDAO() {
        return new TornSettingOcFlowNodeDAO();
    }

    @Bean
    TornSettingOcFlowEdgeDAO tornSettingOcFlowEdgeDAO() {
        return new TornSettingOcFlowEdgeDAO();
    }

    @Bean
    TornSettingOcFlowManager tornSettingOcFlowManager(TornSettingOcFlowNodeDAO flowNodeDAO,
                                                      TornSettingOcFlowEdgeDAO flowEdgeDAO) {
        return new TornSettingOcFlowManager(flowNodeDAO, flowEdgeDAO);
    }

    @Bean
    OcFlowEvaluator ocFlowEvaluator() {
        return new OcFlowEvaluator();
    }
}
