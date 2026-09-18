package pn.torn.goldeneye.torn.service.stocks.alert.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRuleModeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaReadinessGate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 股票提醒运行时门禁测试,验证正式新入场只允许FORMAL,总开关关闭但有存量批次时仍继续管理,
 * α影子许可独立于正式新入场,新买入开关缺失按false处理,以及历史PENDING通知独立投递。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.08.02
 */
@DisplayName("股票提醒运行时门禁测试")
@ExtendWith(MockitoExtension.class)
class StockAlertRuntimeGateTest {

    @Mock
    private SysSettingManager sysSettingManager;
    @Mock
    private TornStockVirtualBatchDAO virtualBatchDao;
    @Mock
    private TornStockNoticeAuditDAO noticeAuditDao;
    @Mock
    private StockAlphaReadinessGate alphaReadinessGate;

    private StockAlertRuntimeGate runtimeGate;

    @BeforeEach
    void setUp() {
        runtimeGate = new StockAlertRuntimeGate(sysSettingManager, virtualBatchDao, noticeAuditDao,
                alphaReadinessGate);
        lenient().when(alphaReadinessGate.isReady()).thenReturn(true);
    }

    @Test
    @DisplayName("总开关关闭且无活跃批次无影子许可_不构建轮次不发送通知")
    void evaluate_alertDisabledNoBatchesNoNotices_stopsAll() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("SHADOW");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(false);
        when(noticeAuditDao.existsSendableNotices()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertFalse(decision.shouldBuildRounds());
        assertFalse(decision.existsActiveBatches());
        assertFalse(decision.allowAlphaShadow());
        assertFalse(decision.allowNewEntry());
        assertFalse(decision.shouldSendPendingNotices());
    }

    @Test
    @DisplayName("总开关关闭但存在活跃批次_继续管理存量且禁止新买入")
    void evaluate_alertDisabledWithActiveBatches_managesExistingAndBlocksNewEntry() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("SHADOW");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(true);
        when(noticeAuditDao.existsSendableNotices()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertTrue(decision.shouldBuildRounds(), "存在活跃批次必须继续构建存量管理所需轮次");
        assertTrue(decision.existsActiveBatches());
        assertFalse(decision.allowNewEntry(), "总开关关闭时新买入必须关闭");
    }

    @Test
    @DisplayName("新买入开关缺失_按false处理且不阻断存量管理")
    void evaluate_newEntryMissingDefaultsToFalse() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn(null);
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("FORMAL");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(true);
        when(noticeAuditDao.existsSendableNotices()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertTrue(decision.shouldBuildRounds());
        assertFalse(decision.allowNewEntry(), "配置缺失必须按false处理,禁止从总开关推导为true");
        assertTrue(decision.existsActiveBatches());
    }

    @Test
    @DisplayName("规则模式OFF_不阻断存量管理但禁止新买入")
    void evaluate_ruleModeOff_doesNotBlockExistingButBlocksNewEntry() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("OFF");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(true);
        when(noticeAuditDao.existsSendableNotices()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertEquals(StockRuleModeEnum.OFF, decision.ruleMode());
        assertTrue(decision.existsActiveBatches(), "RULE_MODE=OFF不得阻断存量批次管理");
        assertFalse(decision.allowNewEntry(), "RULE_MODE=OFF禁止新买入");
    }

    @Test
    @DisplayName("α影子开关打开_独立于总开关与活跃批次产生轮次构建义务")
    void evaluate_alphaShadowEnabledWithoutBatches_buildsRoundsForShadowObservation() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("SHADOW");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(false);
        when(noticeAuditDao.existsSendableNotices()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertTrue(decision.shouldBuildRounds(), "α影子观察开启必须产生轮次与行情数据义务");
        assertTrue(decision.allowAlphaShadow());
        assertFalse(decision.allowNewEntry(), "α影子许可不得推导出正式新入场许可");
        assertFalse(decision.existsActiveBatches());
    }

    @Test
    @DisplayName("存在PENDING通知且正式消息开关允许_独立于轮次总开关投递")
    void evaluate_pendingNoticesWithFormalNoticeEnabled_sendsIndependently() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("SHADOW");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(false);
        when(noticeAuditDao.existsSendableNotices()).thenReturn(true);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertFalse(decision.shouldBuildRounds());
        assertTrue(decision.shouldSendPendingNotices(), "历史PENDING通知不依赖轮次总开关");
    }

    @Test
    @DisplayName("FORMAL模式_readiness与新入场开关均通过时允许正式新入场")
    void evaluate_formalModeWithReadinessAndSwitch_allowsFormalNewEntry() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("FORMAL");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(false);
        when(noticeAuditDao.existsSendableNotices()).thenReturn(true);
        when(alphaReadinessGate.isReady()).thenReturn(true);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertTrue(decision.shouldBuildRounds());
        assertTrue(decision.allowNewEntry(), "只有FORMAL才允许正式新入场");
        assertEquals(StockRuleModeEnum.FORMAL, decision.ruleMode());
        assertFalse(decision.shouldSendPendingNotices(), "正式消息关闭只阻止发送");
    }

    @Test
    @DisplayName("SHADOW模式_即使readiness与新入场开关通过也不允许正式新入场")
    void evaluate_shadowMode_blocksFormalNewEntry() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("SHADOW");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(true);
        when(noticeAuditDao.existsSendableNotices()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertEquals(StockRuleModeEnum.SHADOW, decision.ruleMode());
        assertFalse(decision.allowNewEntry(), "SHADOW不得创建VIP_ALPHA正式批次");
        assertTrue(decision.existsActiveBatches(), "SHADOW不得停止存量批次管理");
    }

    @Test
    @DisplayName("PROVISIONAL模式_不得借用VIP_ALPHA的10B正式资金语义")
    void evaluate_provisionalMode_blocksFormalNewEntry() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("PROVISIONAL");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(true);
        when(noticeAuditDao.existsSendableNotices()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertEquals(StockRuleModeEnum.PROVISIONAL, decision.ruleMode());
        assertFalse(decision.allowNewEntry(), "PROVISIONAL没有小规模资金契约,不得使用10B/100%正式组合");
        assertTrue(decision.existsActiveBatches());
    }

    @Test
    @DisplayName("FORMAL模式_readiness不通过不得新入场")
    void evaluate_formalModeWithoutReadiness_blocksNewEntry() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("FORMAL");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(true);
        when(noticeAuditDao.existsSendableNotices()).thenReturn(false);
        when(alphaReadinessGate.isReady()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertFalse(decision.allowNewEntry(), "Alpha readiness未通过不得新入场");
        assertTrue(decision.existsActiveBatches(), "readiness不影响存量批次管理");
    }
}
