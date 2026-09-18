package pn.torn.goldeneye.torn.service.stocks.alert.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRuleModeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaReadinessGate;

/**
 * 股票提醒运行时门禁 - 统一计算轮次构建、存量管理、正式新买入、α影子运行与通知投递判定
 * <p>
 * 定时调度入口与启动补偿必须复用本服务,避免双套判断导致总开关关闭时遗弃存量持仓。
 * <p>
 * 核心语义:
 * <ul>
 *   <li>总开关 {@code VIP_STOCK_ALERT_ENABLED} 关闭时,只要存在活跃批次,仍应构建存量管理所需轮次
 *       (退出、恢复、灾难关闭、冷却),仅禁止新买入;</li>
 *   <li>新买入开关 {@code VIP_STOCK_NEW_ENTRY_ENABLED} 缺失或为false按false处理,禁止从总开关推导为true;</li>
 *   <li>正式新入场只允许 {@code FORMAL}:{@code SHADOW}/{@code PROVISIONAL} 不得借用{@code VIP_ALPHA}
 *       的10B、100%正式组合语义,也不得创建正式批次;</li>
 *   <li>α影子许可 {@code VIP_STOCK_ALPHA_SHADOW_ENABLED} 独立于新买入开关:影子开关打开即产生轮次与行情
 *       数据义务,使影子轨道能够从自己的相位起算点开始观察,同时不触真钱、不投递任何通知;</li>
 *   <li>规则模式 OFF 只禁止买入研究事件与正式接纳,不阻断存量批次管理;</li>
 *   <li>历史PENDING通知投递独立于轮次开关,由正式消息开关单独决定。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.6.5
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
    private final StockAlphaReadinessGate alphaReadinessGate;

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
        boolean allowAlphaShadow = isEnabled(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED);
        StockRuleModeEnum ruleMode = StockRuleModeEnum.resolve(
                sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE));

        boolean existsActiveBatches = virtualBatchDao.existsActiveBatches();
        boolean existsPendingNotices = noticeAuditDao.existsSendableNotices();

        boolean shouldBuildRounds = alertEnabled || existsActiveBatches || allowAlphaShadow;
        // 正式新入场是"总开关 ∧ 新入场开关 ∧ 规则模式为FORMAL ∧ Alpha readiness"的合取:
        // SHADOW/PROVISIONAL 没有独立的小规模资金、槽位和消息契约,不得借用VIP_ALPHA的10B、100%正式语义。
        boolean allowNewEntry = alertEnabled && newEntryEnabled
                && ruleMode == StockRuleModeEnum.FORMAL && alphaReadinessGate.isReady();
        boolean shouldSendPendingNotices = formalNoticeEnabled && existsPendingNotices;

        RuntimeDecision decision = new RuntimeDecision(
                shouldBuildRounds, allowNewEntry, allowAlphaShadow,
                shouldSendPendingNotices, ruleMode, existsActiveBatches);
        log.debug("股票提醒运行时门禁判定: alertEnabled={}, newEntryEnabled={}, alphaShadowEnabled={}, ruleMode={}, "
                        + "existsActiveBatches={}, existsPendingNotices={}, "
                        + "shouldBuildRounds={}, allowNewEntry={}, allowAlphaShadow={}, shouldSendPendingNotices={}",
                alertEnabled, newEntryEnabled, allowAlphaShadow, ruleMode.getCode(), existsActiveBatches,
                existsPendingNotices, decision.shouldBuildRounds(),
                decision.allowNewEntry(), decision.allowAlphaShadow(), decision.shouldSendPendingNotices());
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
     * 运行时判定结果
     *
     * @param shouldBuildRounds        是否构建轮次(含存量管理或α影子观察所需轮次)
     * @param allowNewEntry            是否允许正式新入场(唯一正式许可:仅{@code FORMAL}模式成立)
     * @param allowAlphaShadow         是否允许α影子轨道运行(独立于正式新入场,影子不触真钱不投递)
     * @param shouldSendPendingNotices 是否应投递历史PENDING通知
     * @param ruleMode                 当前规则模式
     * @param existsActiveBatches      是否存在活跃存量批次(兼作存量管理判定、日志与测试断言依据)
     */
    public record RuntimeDecision(
            boolean shouldBuildRounds,
            boolean allowNewEntry,
            boolean allowAlphaShadow,
            boolean shouldSendPendingNotices,
            StockRuleModeEnum ruleMode,
            boolean existsActiveBatches) {
    }
}
