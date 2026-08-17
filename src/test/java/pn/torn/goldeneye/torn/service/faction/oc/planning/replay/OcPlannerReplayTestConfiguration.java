package pn.torn.goldeneye.torn.service.faction.oc.planning.replay;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.setting.*;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.torn.manager.setting.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.api.OcNewTeamPlanRenderer;
import pn.torn.goldeneye.torn.service.faction.oc.planning.api.OcRefreshInstructionPlanner;
import pn.torn.goldeneye.torn.service.faction.oc.planning.chain.OcChainPlanningService;
import pn.torn.goldeneye.torn.service.faction.oc.planning.chain.OcExistingTimelineReconstructor;
import pn.torn.goldeneye.torn.service.faction.oc.planning.evidence.OcRewardEvidenceCalculator;
import pn.torn.goldeneye.torn.service.faction.oc.planning.policy.OcRefreshModeSelector;
import pn.torn.goldeneye.torn.service.faction.oc.planning.snapshot.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcReplanWindowCalculator;

import javax.sql.DataSource;

/**
 * OC规划隔离回放的最小测试上下文。只注册真实只读依赖的DataSource、事务管理器、
 * MyBatis基础设施、OC规划DAO/Manager/纯引擎组件，不启动GoldenEyeApplication，
 * 不扫描NapCat、Torn API、Lark、Redis或任务调度器。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@TestConfiguration
@MapperScan("pn.torn.goldeneye.repository.mapper")
@ImportAutoConfiguration(MybatisPlusAutoConfiguration.class)
public class OcPlannerReplayTestConfiguration {

    /**
     * 构造回放专用DataSource。
     *
     * @param url      数据库连接URL
     * @param username 数据库用户名
     * @param password 数据库密码
     * @return DataSource
     */
    @Bean
    DataSource dataSource(
            @Value("${spring.datasource.url:jdbc:postgresql://localhost:14321/golden-eye}")
            String url,
            @Value("${spring.datasource.username:postgres}") String username,
            @Value("${spring.datasource.password:1qazXSW@}") String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    /**
     * 构造回放专用JdbcTemplate，用于只读守卫的受控写尝试和业务表零写计数。
     *
     * @param dataSource 数据源
     * @return JdbcTemplate
     */
    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 构造回放专用事务管理器。
     *
     * @param dataSource 数据源
     * @return 事务管理器
     */
    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    TornFactionOcDAO tornFactionOcDAO() {
        return new TornFactionOcDAO();
    }

    @Bean
    TornFactionOcSlotDAO tornFactionOcSlotDAO() {
        return new TornFactionOcSlotDAO();
    }

    @Bean
    TornFactionOcUserDAO tornFactionOcUserDAO() {
        return new TornFactionOcUserDAO();
    }

    @Bean
    TornUserDAO tornUserDAO() {
        return new TornUserDAO();
    }

    @Bean
    TornSettingOcDAO tornSettingOcDAO() {
        return new TornSettingOcDAO();
    }

    @Bean
    TornSettingOcSlotDAO tornSettingOcSlotDAO() {
        return new TornSettingOcSlotDAO();
    }

    @Bean
    TornSettingFactionOcDisableDAO tornSettingFactionOcDisableDAO() {
        return new TornSettingFactionOcDisableDAO();
    }

    @Bean
    TornSettingFactionOcSlotDAO tornSettingFactionOcSlotDAO() {
        return new TornSettingFactionOcSlotDAO();
    }

    @Bean
    TornSettingOcCoefficientDAO tornSettingOcCoefficientDAO() {
        return new TornSettingOcCoefficientDAO();
    }

    @Bean
    TornSettingOcPlanProfileDAO tornSettingOcPlanProfileDAO() {
        return new TornSettingOcPlanProfileDAO();
    }

    @Bean
    TornSettingOcChainDAO tornSettingOcChainDAO() {
        return new TornSettingOcChainDAO();
    }

    @Bean
    TornSettingFactionOcPlanDAO tornSettingFactionOcPlanDAO() {
        return new TornSettingFactionOcPlanDAO();
    }

    @Bean
    TornSettingFactionOcPlanningPolicyDAO tornSettingFactionOcPlanningPolicyDAO() {
        return new TornSettingFactionOcPlanningPolicyDAO();
    }

    @Bean
    TornSettingOcManager tornSettingOcManager(TornSettingOcDAO tornSettingOcDAO) {
        return new TornSettingOcManager(tornSettingOcDAO);
    }

    @Bean
    TornSettingOcSlotManager tornSettingOcSlotManager(
            TornSettingOcSlotDAO tornSettingOcSlotDAO) {
        return new TornSettingOcSlotManager(tornSettingOcSlotDAO);
    }

    @Bean
    TornSettingFactionOcManager tornSettingFactionOcManager(
            TornSettingFactionOcDisableDAO disableDAO,
            TornSettingFactionOcSlotDAO slotDAO) {
        return new TornSettingFactionOcManager(disableDAO, slotDAO);
    }

    @Bean
    TornSettingOcCoefficientManager tornSettingOcCoefficientManager(
            TornSettingOcCoefficientDAO coefficientDAO) {
        return new TornSettingOcCoefficientManager(coefficientDAO);
    }

    @Bean
    TornSettingOcPlanningManager tornSettingOcPlanningManager(
            TornSettingOcPlanProfileDAO profileDAO,
            TornSettingOcChainDAO chainDAO,
            TornSettingFactionOcPlanDAO factionPlanDAO,
            TornSettingFactionOcPlanningPolicyDAO policyDAO) {
        return new TornSettingOcPlanningManager(profileDAO, chainDAO, factionPlanDAO,
                policyDAO);
    }

    @Bean
    OcFactionPlanningPolicyResolver ocFactionPlanningPolicyResolver(
            TornSettingOcPlanningManager planningManager) {
        return new OcFactionPlanningPolicyResolver(planningManager);
    }

    @Bean
    OcPlanCatalogValidator ocPlanCatalogValidator(
            TornSettingOcManager ocManager,
            TornSettingOcSlotManager slotManager,
            TornSettingOcCoefficientManager coefficientManager,
            TornSettingOcPlanningManager planningManager) {
        return new OcPlanCatalogValidator(ocManager, slotManager, coefficientManager,
                planningManager);
    }

    @Bean
    OcRewardEvidenceCalculator ocRewardEvidenceCalculator() {
        return new OcRewardEvidenceCalculator();
    }

    @Bean
    OcCurrentOccupancyCalculator ocCurrentOccupancyCalculator() {
        return new OcCurrentOccupancyCalculator();
    }

    @Bean
    OcReplanWindowCalculator ocReplanWindowCalculator() {
        return new OcReplanWindowCalculator();
    }

    @Bean
    OcChainPlanningService ocChainPlanningService() {
        return new OcChainPlanningService();
    }

    @Bean
    OcExistingTimelineReconstructor ocExistingTimelineReconstructor() {
        return new OcExistingTimelineReconstructor();
    }

    @Bean
    OcPlanningSnapshotLoader ocPlanningSnapshotLoader(
            TornFactionOcDAO ocDAO,
            TornFactionOcSlotDAO slotDAO,
            TornFactionOcUserDAO ocUserDAO,
            TornUserDAO userDAO,
            TornSettingOcSlotManager slotManager,
            TornSettingFactionOcManager factionOcManager,
            TornSettingOcCoefficientManager coefficientManager,
            TornSettingOcPlanningManager planningManager,
            OcFactionPlanningPolicyResolver policyResolver,
            OcPlanCatalogValidator catalogValidator,
            OcRewardEvidenceCalculator rewardEvidenceCalculator) {
        return new OcPlanningSnapshotLoader(ocDAO, slotDAO, ocUserDAO, userDAO,
                slotManager, factionOcManager, coefficientManager, planningManager,
                policyResolver, catalogValidator, rewardEvidenceCalculator);
    }

    @Bean
    OcRefreshSafetyRequestFactory ocRefreshSafetyRequestFactory(
            OcChainPlanningService chainPlanningService,
            OcExistingTimelineReconstructor timelineReconstructor,
            OcRewardEvidenceCalculator rewardEvidenceCalculator) {
        return new OcRefreshSafetyRequestFactory(chainPlanningService,
                timelineReconstructor, rewardEvidenceCalculator);
    }

    @Bean
    OcRefreshModeSelector ocRefreshModeSelector() {
        return new OcRefreshModeSelector();
    }

    @Bean
    OcRefreshInstructionPlanner ocRefreshInstructionPlanner(
            OcRefreshSafetyRequestFactory requestFactory,
            OcRefreshModeSelector modeSelector,
            OcCurrentOccupancyCalculator occupancyCalculator,
            OcReplanWindowCalculator replanWindowCalculator) {
        return new OcRefreshInstructionPlanner(requestFactory, modeSelector,
                occupancyCalculator, replanWindowCalculator);
    }

    @Bean
    OcNewTeamPlanRenderer ocNewTeamPlanRenderer() {
        return new OcNewTeamPlanRenderer();
    }

    @Bean
    OcPlanningReadOnlyGuard ocPlanningReadOnlyGuard(
            PlatformTransactionManager transactionManager,
            pn.torn.goldeneye.repository.mapper.faction.oc.OcPlanningReadOnlyProbeMapper probeMapper) {
        return new OcPlanningReadOnlyGuard(transactionManager, probeMapper);
    }
}
