package pn.torn.goldeneye.torn.manager.setting;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.constants.torn.enums.TornOcIncomeModeEnum;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignFactionDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignOcDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignFactionDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignOcDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.FactionOcExclusion;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * 大锅饭配置派生门面单元测试。
 *
 * <p>覆盖派生规则各主路径：名单组装、NULL/日期分组归并的排除规则、帮派集合、扫描起点取最小值
 * 与全NULL回落当月月初、模式默认值。DAO列表以Mockito桩替换，不依赖数据库。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("大锅饭配置派生门面测试")
class TornSettingOcReassignManagerTest {
    private static final long FACTION_PN = 20465L;
    private static final long FACTION_NOV = 16335L;
    private static final LocalDateTime AUG_FROM = LocalDateTime.of(2026, 8, 1, 0, 0, 0);
    private static final LocalDateTime JUL_FROM = LocalDateTime.of(2026, 7, 1, 0, 0, 0);

    @Mock
    private TornSettingOcReassignFactionDAO reassignFactionDao;
    @Mock
    private TornSettingOcReassignOcDAO reassignOcDao;

    private TornSettingOcReassignManager reassignManager;

    @BeforeEach
    void setUp() {
        reassignManager = new TornSettingOcReassignManager(reassignFactionDao, reassignOcDao);
        ReflectionTestUtils.setField(reassignManager, "reassignManager", reassignManager);
    }

    @Test
    @DisplayName("名单组装：帮派启用且范围行启用的oc_name，禁用行与未启用帮派排除")
    void getRotationOcNames_assemblesEnabledRowsOnly() {
        stubFactionList(factionRow(FACTION_PN, TornOcIncomeModeEnum.COEFFICIENT, true));
        stubOcList(List.of(
                scopeRow(FACTION_PN, "Ace in the Hole", null, true),
                scopeRow(FACTION_PN, "Lock Stock", AUG_FROM, true),
                scopeRow(FACTION_PN, "Break the Bank", null, false)));

        List<String> names = reassignManager.getRotationOcNames(FACTION_PN);

        assertEquals(List.of("Ace in the Hole", "Lock Stock"), names);
        assertTrue(reassignManager.getRotationOcNames(FACTION_NOV).isEmpty(), "未启用帮派返回空名单");
    }

    @Test
    @DisplayName("排除规则：同帮派按生效时间分组归并，NULL组在前、日期组升序")
    void getExclusionRules_groupsByEffectiveFrom() {
        stubFactionList(factionRow(FACTION_PN, TornOcIncomeModeEnum.COEFFICIENT, true));
        stubOcList(List.of(
                scopeRow(FACTION_PN, "Ace in the Hole", null, true),
                scopeRow(FACTION_PN, "Break the Bank", null, true),
                scopeRow(FACTION_PN, "Lock Stock", AUG_FROM, true),
                scopeRow(FACTION_PN, "Hostile Takeover", AUG_FROM, true),
                scopeRow(FACTION_PN, "Manifest Cruelty", JUL_FROM, true)));

        List<FactionOcExclusion> rules = reassignManager.getExclusionRules().get(FACTION_PN);

        assertEquals(3, rules.size());
        assertNull(rules.get(0).getEffectiveFrom());
        assertEquals(List.of("Ace in the Hole", "Break the Bank"), rules.get(0).getOcList());
        assertEquals(JUL_FROM, rules.get(1).getEffectiveFrom());
        assertEquals(List.of("Manifest Cruelty"), rules.get(1).getOcList());
        assertEquals(AUG_FROM, rules.get(2).getEffectiveFrom());
        assertEquals(List.of("Lock Stock", "Hostile Takeover"), rules.get(2).getOcList());
    }

    @Test
    @DisplayName("帮派集合只包含启用开关的帮派")
    void getReassignFactionList_containsEnabledOnly() {
        stubFactionList(
                factionRow(FACTION_PN, TornOcIncomeModeEnum.COEFFICIENT, true),
                factionRow(FACTION_NOV, TornOcIncomeModeEnum.EQUAL, false));

        assertEquals(List.of(FACTION_PN), reassignManager.getReassignFactionList());
    }

    @Test
    @DisplayName("扫描起点取非NULL生效时间最小值，全NULL回落执行月月初")
    void resolveIncomeStartTime_minEffectiveFromOrMonthStart() {
        stubOcList(List.of(
                scopeRow(FACTION_PN, "Ace in the Hole", null, true),
                scopeRow(FACTION_PN, "Lock Stock", AUG_FROM, true),
                scopeRow(FACTION_PN, "Hostile Takeover", LocalDateTime.of(2026, 9, 1, 0, 0, 0), true)));

        LocalDateTime execTime = LocalDateTime.of(2026, 9, 14, 12, 0);
        assertEquals(AUG_FROM, reassignManager.resolveIncomeStartTime(FACTION_PN, execTime));

        stubOcList(List.of(scopeRow(FACTION_PN, "Ace in the Hole", null, true)));
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0, 0),
                reassignManager.resolveIncomeStartTime(FACTION_PN, execTime));
    }

    @Test
    @DisplayName("收益模式读帮派行，无帮派行时默认COEFFICIENT")
    void getIncomeMode_defaultsToCoefficient() {
        stubFactionList();
        assertEquals(TornOcIncomeModeEnum.COEFFICIENT, reassignManager.getIncomeMode(FACTION_NOV));

        stubFactionList(factionRow(FACTION_NOV, TornOcIncomeModeEnum.EQUAL, true));
        assertEquals(TornOcIncomeModeEnum.EQUAL, reassignManager.getIncomeMode(FACTION_NOV));
    }

    /**
     * 桩帮派开关列表加载链。
     *
     * @param rows 帮派开关行
     */
    private void stubFactionList(TornSettingOcReassignFactionDO... rows) {
        LambdaQueryChainWrapper<TornSettingOcReassignFactionDO> wrapper = mock(LambdaQueryChainWrapper.class);
        doReturn(wrapper).when(reassignFactionDao).lambdaQuery();
        doReturn(wrapper).when(wrapper).eq(any(), any());
        doReturn(List.of(rows)).when(wrapper).list();
    }

    /**
     * 桩范围行列表加载链。
     *
     * @param rows 范围行
     */
    private void stubOcList(List<TornSettingOcReassignOcDO> rows) {
        LambdaQueryChainWrapper<TornSettingOcReassignOcDO> wrapper = mock(LambdaQueryChainWrapper.class);
        doReturn(wrapper).when(reassignOcDao).lambdaQuery();
        doReturn(wrapper).when(wrapper).eq(any(), any());
        doReturn(rows).when(wrapper).list();
    }

    /**
     * 构造帮派开关行。
     */
    private TornSettingOcReassignFactionDO factionRow(long factionId, TornOcIncomeModeEnum mode, boolean enabled) {
        TornSettingOcReassignFactionDO row = new TornSettingOcReassignFactionDO();
        row.setFactionId(factionId);
        row.setIncomeMode(mode.getCode());
        row.setEnabled(enabled);
        return row;
    }

    /**
     * 构造范围行。
     */
    private TornSettingOcReassignOcDO scopeRow(long factionId, String ocName, LocalDateTime effectiveFrom,
                                               boolean enabled) {
        TornSettingOcReassignOcDO row = new TornSettingOcReassignOcDO();
        row.setFactionId(factionId);
        row.setOcName(ocName);
        row.setRank(8);
        row.setEffectiveFrom(effectiveFrom);
        row.setEnabled(enabled);
        return row;
    }
}
