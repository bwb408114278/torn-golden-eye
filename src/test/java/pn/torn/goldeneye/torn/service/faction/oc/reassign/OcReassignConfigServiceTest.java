package pn.torn.goldeneye.torn.service.faction.oc.reassign;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcIncomeModeEnum;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionOcPlanDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignFactionDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignOcDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcPlanDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignOcDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcCoefficientManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcPlanningManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcReassignManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 大锅饭配置编排服务测试（真实校验器+共享库种子数据）。
 *
 * <p>添加/开启走真实事务提交以验证事务提交后缓存驱逐注册，因此不使用测试级回滚，
 * 改为@AfterEach按测试帮派(PTA 9356，非生产大锅饭帮派)物理删除写入行并刷新缓存，
 * 保证开发库零残留。失败分支用例(目录缺失/链缺前序/幂等/CCRC系数)全部为读路径零写入。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("大锅饭配置编排服务测试")
class OcReassignConfigServiceTest {
    /**
     * PTA帮派：真实存在于帮派设置但不在生产大锅饭帮派中，用作指令写入目标
     */
    private static final long TEST_FACTION_ID = 9356L;
    private static final long OPERATOR_ID = 8806001L;
    private static final LocalDateTime MONTH_START =
            LocalDate.now().withDayOfMonth(1).atStartOfDay();

    @Autowired
    private OcReassignConfigService reassignConfigService;
    @Autowired
    private TornSettingOcReassignFactionDAO reassignFactionDao;
    @Autowired
    private TornSettingOcReassignOcDAO reassignOcDao;
    @Autowired
    private TornSettingFactionOcPlanDAO factionPlanDao;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private TornSettingOcReassignManager reassignManager;
    @MockitoSpyBean
    private TornSettingOcPlanningManager planningManager;
    @MockitoSpyBean
    private TornSettingOcCoefficientManager coefficientManager;

    @AfterEach
    void cleanup() {
        Mockito.reset(reassignManager, planningManager, coefficientManager);
        jdbcTemplate.update("DELETE FROM torn_setting_oc_reassign_oc WHERE faction_id = ?", TEST_FACTION_ID);
        jdbcTemplate.update("DELETE FROM torn_setting_oc_reassign_faction WHERE faction_id = ?", TEST_FACTION_ID);
        jdbcTemplate.update("DELETE FROM torn_setting_faction_oc_plan WHERE faction_id = ? AND oc_name IN (?, ?)",
                TEST_FACTION_ID, "Stacking the Deck", "Cleared for Takeoff");
        reassignManager.refreshCache();
        planningManager.refreshCache();
    }

    @Test
    @DisplayName("COEFFICIENT帮派添加链根成功：范围行+规划行落库、缓存在事务提交后驱逐")
    void addOc_coefficientFaction_persistsRowsAndEvictsAfterCommit() {
        reassignConfigService.openFaction(TEST_FACTION_ID, TornOcIncomeModeEnum.COEFFICIENT, OPERATOR_ID);

        OcReassignConfigService.AddOcResult result =
                reassignConfigService.addOc(TEST_FACTION_ID, TornConstants.OC_NAME_STACKING_THE_DECK, null, OPERATOR_ID);

        assertTrue(result.success());
        assertNull(result.failureReason());
        assertEquals(MONTH_START, result.effectiveFrom());
        assertTrue(result.planSynced(), "PTA无Stacking the Deck规划行时应同步插入");
        assertFalse(result.profileMissing(), "Stacking the Deck存在新队规划档案");

        TornSettingOcReassignOcDO scopeRow = reassignOcDao.lambdaQuery()
                .eq(TornSettingOcReassignOcDO::getFactionId, TEST_FACTION_ID)
                .eq(TornSettingOcReassignOcDO::getOcName, TornConstants.OC_NAME_STACKING_THE_DECK)
                .one();
        assertEquals(8, scopeRow.getRank(), "rank取目录值");
        assertEquals(MONTH_START, scopeRow.getEffectiveFrom(), "未传生效日期时默认当月1日");
        assertTrue(scopeRow.getEnabled());

        boolean planRowExists = factionPlanDao.lambdaQuery()
                .eq(TornSettingFactionOcPlanDO::getFactionId, TEST_FACTION_ID)
                .eq(TornSettingFactionOcPlanDO::getOcName, TornConstants.OC_NAME_STACKING_THE_DECK)
                .exists();
        assertTrue(planRowExists, "规划范围行与范围行同步落库");

        verify(reassignManager, atLeastOnce()).refreshCache();
    }

    @Test
    @DisplayName("EQUAL帮派添加成功并跳过系数完整性校验")
    void addOc_equalFaction_skipsCoefficientCheck() {
        reassignConfigService.openFaction(TEST_FACTION_ID, TornOcIncomeModeEnum.EQUAL, OPERATOR_ID);

        OcReassignConfigService.AddOcResult result = reassignConfigService
                .addOc(TEST_FACTION_ID, "Cleared for Takeoff", LocalDate.of(2026, 9, 1), OPERATOR_ID);

        assertTrue(result.success(), "平分模式无系数依赖，Cleared for Takeoff应添加成功");
        assertEquals(LocalDate.of(2026, 9, 1).atStartOfDay(), result.effectiveFrom());
        verify(coefficientManager, never()).hasCompleteCoefficients(anyLong(), anyString(), anyInt(), anyList());
    }

    @Test
    @DisplayName("CCRC系数自有行不回落faction_id=0：无自有行的新OC必须拒绝(fail-closed)")
    void addOc_ccrcOwnRowsWithoutTargetOc_rejected() {
        OcReassignConfigService.AddOcResult result = reassignConfigService
                .addOc(TornConstants.FACTION_CCRC_ID, TornConstants.OC_NAME_STACKING_THE_DECK, null, OPERATOR_ID);

        assertFalse(result.success());
        assertTrue(result.failureReason().startsWith("失败: 系数不完整, 缺少岗位系数: "),
                "实际回执: " + result.failureReason());
        boolean scopeRowExists = reassignOcDao.lambdaQuery()
                .eq(TornSettingOcReassignOcDO::getFactionId, TornConstants.FACTION_CCRC_ID)
                .eq(TornSettingOcReassignOcDO::getOcName, TornConstants.OC_NAME_STACKING_THE_DECK)
                .exists();
        assertFalse(scopeRowExists, "校验失败不得写入范围行");
    }

    @Test
    @DisplayName("链尾缺前序节点拒绝：添加Crane Reaction需先加入Manifest Cruelty")
    void addOc_chainTailMissingPredecessor_rejected() {
        reassignConfigService.openFaction(TEST_FACTION_ID, TornOcIncomeModeEnum.EQUAL, OPERATOR_ID);

        OcReassignConfigService.AddOcResult result =
                reassignConfigService.addOc(TEST_FACTION_ID, TornConstants.OC_NAME_CRANE_REACTION, null, OPERATOR_ID);

        assertFalse(result.success());
        assertEquals("失败: 链式OC缺少前序节点: 添加[" + TornConstants.OC_NAME_CRANE_REACTION
                + "]需先加入[" + TornConstants.OC_NAME_MANIFEST_CRUELTY + "]", result.failureReason());
        boolean scopeRowExists = reassignOcDao.lambdaQuery()
                .eq(TornSettingOcReassignOcDO::getFactionId, TEST_FACTION_ID)
                .eq(TornSettingOcReassignOcDO::getOcName, TornConstants.OC_NAME_CRANE_REACTION)
                .exists();
        assertFalse(scopeRowExists, "校验失败不得写入范围行");
    }

    @Test
    @DisplayName("目录缺失拒绝并提示先执行OC校准")
    void addOc_catalogMissing_rejected() {
        reassignConfigService.openFaction(TEST_FACTION_ID, TornOcIncomeModeEnum.EQUAL, OPERATOR_ID);

        OcReassignConfigService.AddOcResult result =
                reassignConfigService.addOc(TEST_FACTION_ID, "No Such OC", null, OPERATOR_ID);

        assertFalse(result.success());
        assertEquals("失败: 目录中不存在该OC, 请先执行[OC校准]触发目录自动同步", result.failureReason());
    }

    @Test
    @DisplayName("幂等拒绝：PN已存在的Break the Bank回执现值")
    void addOc_idempotent_rejectedWithExistingValue() {
        OcReassignConfigService.AddOcResult result = reassignConfigService
                .addOc(TornConstants.FACTION_PN_ID, TornConstants.OC_NAME_BREAK_THE_BANK, null, OPERATOR_ID);

        assertFalse(result.success());
        assertEquals("失败: 该OC已在大锅饭名单中, 生效时间: 始终", result.failureReason());
    }

    @Test
    @DisplayName("开启帮派落库，重复开启拒绝")
    void openFaction_persistsAndRejectsDuplicate() {
        OcReassignConfigService.OpenResult first =
                reassignConfigService.openFaction(TEST_FACTION_ID, TornOcIncomeModeEnum.COEFFICIENT, OPERATOR_ID);
        assertTrue(first.success());

        OcReassignConfigService.OpenResult second =
                reassignConfigService.openFaction(TEST_FACTION_ID, TornOcIncomeModeEnum.EQUAL, OPERATOR_ID);
        assertFalse(second.success());
        assertEquals("失败: 该帮派已开启大锅饭", second.failureReason());

        List<TornSettingOcReassignOcDO> scopeRows = reassignOcDao.lambdaQuery()
                .eq(TornSettingOcReassignOcDO::getFactionId, TEST_FACTION_ID)
                .list();
        assertTrue(scopeRows.isEmpty(), "开启不写范围行");
    }
}
