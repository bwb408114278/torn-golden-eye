package pn.torn.goldeneye.torn.service.stocks.alert.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRuleModeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;

/**
 * 股票提醒运行时门禁 - 统一计算轮次构建、存量管理、研究义务、新买入与通知投递判定
 * <p>
 * 定时调度入口与启动补偿必须复用本服务,避免双套判断导致总开关关闭时遗弃存量持仓。
 * <p>
 * 核心语义:
 * <ul>
 *   <li>总开关 {@code VIP_STOCK_ALERT_ENABLED} 关闭时,只要存在活跃批次,仍应构建存量管理所需轮次
 *       (退出、恢复、灾难关闭、冷却),仅禁止新买入;</li>
 *   <li>新买入开关 {@code VIP_STOCK_NEW_ENTRY_ENABLED} 缺失或为false按false处理,禁止从总开关推导为true;</li>
 *   <li>规则模式 OFF 只禁止买入研究事件、Shadow新批次和正式接纳,不阻断存量批次管理;</li>
 *   <li>存在未结算拒绝观察时,即使新买入关闭且无活跃持仓,仍应构建观察窗口bar并结算研究义务;</li>
 *   <li>历史PENDING通知投递独立于轮次开关,由正式消息开关单独决定。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlertRuntimeGate {

    /**
     * 开关启用标识
     */
    private static final String SETTING_ENABLED_VALUE = "true";

    private final SysSettingManager sysSettingManager;
    private final TornStockVirtualBatchDAO virtualBatchDao;
    private final TornStockNoticeAuditDAO noticeAuditDao;
    private final TornStockSignalEventDAO signalEventDao;

    /**
     * 计算当前运行时判定结果。
     * <p>
     * 一次性读取配置与存在性查询,返回 {@link RuntimeDecision},定时入口与启动补偿共用。
     *
     * @return 当前运行时判定
     */
    public RuntimeDecision evaluate() {
        boolean alertEnabled = isEnabled(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED);
        boolean newEntryEnabled = isEnabled(SettingConstants.KEY_VIP_STOCK_NEW_ENTRY_ENABLED);
        boolean formalNoticeEnabled = isEnabled(SettingConstants.KEY_VIP_STOCK_FORMAL_NOTICE_ENABLED);
        StockRuleModeEnum ruleMode = resolveRuleMode();

        boolean existsActiveBatches = virtualBatchDao.existsActiveBatches();
        boolean existsPendingNotices = noticeAuditDao.existsPendingNotices();
        boolean existsPendingRejectedObservationEvents =
                signalEventDao.existsPendingRejectedObservationEvents();

        boolean shouldBuildRounds = alertEnabled || existsActiveBatches || existsPendingRejectedObservationEvents;
        boolean allowNewEntry = alertEnabled && newEntryEnabled && ruleMode != StockRuleModeEnum.OFF;
        boolean shouldSendPendingNotices = formalNoticeEnabled && existsPendingNotices;

        RuntimeDecision decision = new RuntimeDecision(
                shouldBuildRounds, existsActiveBatches, existsPendingRejectedObservationEvents,
                allowNewEntry, shouldSendPendingNotices, ruleMode, existsActiveBatches,
                existsPendingRejectedObservationEvents);
        log.debug("股票提醒运行时门禁判定: alertEnabled={}, newEntryEnabled={}, ruleMode={}, "
                        + "existsActiveBatches={}, existsPendingNotices={}, existsRejectedObservation={}, "
                        + "shouldBuildRounds={}, manageExistingBatches={}, manageResearchObligations={}, "
                        + "allowNewEntry={}, shouldSendPendingNotices={}",
                alertEnabled, newEntryEnabled, ruleMode.getCode(), existsActiveBatches,
                existsPendingNotices, existsPendingRejectedObservationEvents,
                decision.shouldBuildRounds(), decision.manageExistingBatches(),
                decision.manageResearchObligations(), decision.allowNewEntry(),
                decision.shouldSendPendingNotices());
        return decision;
    }

    /**
     * 读取配置并判断是否等于"true"(忽略大小写);缺失或为空视为false。
     *
     * @param settingKey 配置Key
     * @return 配置值为true返回true;否则false
     */
    private boolean isEnabled(String settingKey) {
        String value = sysSettingManager.getSettingValue(settingKey);
        return SETTING_ENABLED_VALUE.equalsIgnoreCase(value);
    }

    /**
     * 解析规则模式;缺失或非法默认SHADOW。
     *
     * @return 当前规则模式
     */
    private StockRuleModeEnum resolveRuleMode() {
        String modeCode = sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE);
        if (modeCode == null || modeCode.isBlank()) {
            return StockRuleModeEnum.SHADOW;
        }
        try {
            return StockRuleModeEnum.fromCode(modeCode);
        } catch (IllegalArgumentException e) {
            log.warn("规则模式编码无效,默认SHADOW: code={}", modeCode);
            return StockRuleModeEnum.SHADOW;
        }
    }

    /**
     * 运行时判定结果
     *
     * @param shouldBuildRounds                是否构建轮次(含存量管理或拒绝观察义务所需轮次)
     * @param manageExistingBatches            是否存在活跃存量批次需要继续管理
     * @param manageResearchObligations        是否存在未结算拒绝观察需要继续结算研究义务
     * @param allowNewEntry                    是否允许创建新的正式/候选影子批次
     * @param shouldSendPendingNotices         是否应投递历史PENDING通知
     * @param ruleMode                         当前规则模式
     * @param existsActiveBatches              查询到的活跃批次存在性(用于日志与测试断言)
     * @param existsPendingRejectedObservation 查询到的未结算拒绝观察存在性(用于日志与测试断言)
     */
    public record RuntimeDecision(
            boolean shouldBuildRounds,
            boolean manageExistingBatches,
            boolean manageResearchObligations,
            boolean allowNewEntry,
            boolean shouldSendPendingNotices,
            StockRuleModeEnum ruleMode,
            boolean existsActiveBatches,
            boolean existsPendingRejectedObservation) {
    }
}
