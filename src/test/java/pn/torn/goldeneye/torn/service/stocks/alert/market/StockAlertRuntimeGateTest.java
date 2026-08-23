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
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 股票提醒运行时门禁测试,验证总开关关闭但有存量批次/拒绝观察时仍继续管理,
 * 新买入开关缺失按false处理,以及历史PENDING通知独立投递。
 *
 * @author Bai
 * @version 1.2.12
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
    private TornStockSignalEventDAO signalEventDao;

    private StockAlertRuntimeGate runtimeGate;

    @BeforeEach
    void setUp() {
        runtimeGate = new StockAlertRuntimeGate(sysSettingManager, virtualBatchDao, noticeAuditDao, signalEventDao);
    }

    @Test
    @DisplayName("总开关关闭且无活跃批次无拒绝观察_不构建轮次不发送通知")
    void evaluate_alertDisabledNoBatchesNoNotices_stopsAll() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("SHADOW");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(false);
        when(noticeAuditDao.existsPendingNotices()).thenReturn(false);
        when(signalEventDao.existsPendingRejectedObservationEvents()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertFalse(decision.shouldBuildRounds());
        assertFalse(decision.manageExistingBatches());
        assertFalse(decision.manageResearchObligations());
        assertFalse(decision.allowNewEntry());
        assertFalse(decision.shouldSendPendingNotices());
    }

    @Test
    @DisplayName("总开关关闭但存在活跃批次_继续管理存量且禁止新买入")
    void evaluate_alertDisabledWithActiveBatches_managesExistingAndBlocksNewEntry() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("SHADOW");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(true);
        when(noticeAuditDao.existsPendingNotices()).thenReturn(false);
        when(signalEventDao.existsPendingRejectedObservationEvents()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertTrue(decision.shouldBuildRounds(), "存在活跃批次必须继续构建存量管理所需轮次");
        assertTrue(decision.manageExistingBatches());
        assertFalse(decision.allowNewEntry(), "总开关关闭时新买入必须关闭");
    }

    @Test
    @DisplayName("新买入开关缺失_按false处理且不阻断存量管理")
    void evaluate_newEntryMissingDefaultsToFalse() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn(null);
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("SHADOW");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(true);
        when(noticeAuditDao.existsPendingNotices()).thenReturn(false);
        when(signalEventDao.existsPendingRejectedObservationEvents()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertTrue(decision.shouldBuildRounds());
        assertFalse(decision.allowNewEntry(), "配置缺失必须按false处理,禁止从总开关推导为true");
        assertTrue(decision.manageExistingBatches());
    }

    @Test
    @DisplayName("规则模式OFF_不阻断存量管理但禁止新买入")
    void evaluate_ruleModeOff_doesNotBlockExistingButBlocksNewEntry() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("OFF");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(true);
        when(noticeAuditDao.existsPendingNotices()).thenReturn(false);
        when(signalEventDao.existsPendingRejectedObservationEvents()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertEquals(StockRuleModeEnum.OFF, decision.ruleMode());
        assertTrue(decision.manageExistingBatches(), "RULE_MODE=OFF不得阻断存量批次管理");
        assertFalse(decision.allowNewEntry(), "RULE_MODE=OFF禁止新买入");
    }

    @Test
    @DisplayName("无活跃持仓但存在未结算拒绝观察_继续构建观察bar并结算研究义务")
    void evaluate_pendingRejectedObservationWithoutActiveBatches_continuesResearchObligations() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("SHADOW");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(false);
        when(noticeAuditDao.existsPendingNotices()).thenReturn(false);
        when(signalEventDao.existsPendingRejectedObservationEvents()).thenReturn(true);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertTrue(decision.shouldBuildRounds(), "存在未结算拒绝观察必须继续构建观察窗口bar");
        assertTrue(decision.manageResearchObligations());
        assertFalse(decision.manageExistingBatches());
        assertFalse(decision.allowNewEntry());
    }

    @Test
    @DisplayName("存在PENDING通知且正式消息开关允许_独立于轮次总开关投递")
    void evaluate_pendingNoticesWithFormalNoticeEnabled_sendsIndependently() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("SHADOW");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(false);
        when(noticeAuditDao.existsPendingNotices()).thenReturn(true);
        when(signalEventDao.existsPendingRejectedObservationEvents()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertFalse(decision.shouldBuildRounds());
        assertTrue(decision.shouldSendPendingNotices(), "历史PENDING通知不依赖轮次总开关");
    }

    @Test
    @DisplayName("总开关开启新买入开启_正式消息关闭_允许轮次与新买入但不投递通知")
    void evaluate_allEnabledExceptFormalNotice_allowsRoundsAndNewEntry() {
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED)).thenReturn("true");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED)).thenReturn("false");
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE)).thenReturn("PROVISIONAL");
        when(virtualBatchDao.existsActiveBatches()).thenReturn(false);
        when(noticeAuditDao.existsPendingNotices()).thenReturn(true);
        when(signalEventDao.existsPendingRejectedObservationEvents()).thenReturn(false);

        StockAlertRuntimeGate.RuntimeDecision decision = runtimeGate.evaluate();

        assertTrue(decision.shouldBuildRounds());
        assertTrue(decision.allowNewEntry());
        assertEquals(StockRuleModeEnum.PROVISIONAL, decision.ruleMode());
        assertFalse(decision.shouldSendPendingNotices(), "正式消息关闭只阻止发送");
    }
}
