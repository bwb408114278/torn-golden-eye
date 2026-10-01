package pn.torn.goldeneye.torn.service.stocks.alert.settlement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * VIP股票α正式仓年度结算调度器 - 年度边界后的唯一自动触发入口。
 *
 * <p>触发点全部落在年度边界当日(1月1日)的00:05-00:13,使用独立单线程调度器
 * {@code stockAnnualSettlementScheduler},与15分钟轮次、每日摘要和Tornsy巡检互不争用;
 * 刻意避开08:00-09:35的"α决策+执行+入场过期判定"资源敏感区。
 *
 * <p>幂等由结算服务的业务唯一键与ON CONFLICT DO NOTHING保证,因此多触发点安全且不需要分布式锁;
 * 出窗后不再尝试自动补结,由人工流程处理。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VipStockAnnualSettlementScheduler {
    /**
     * 开关开启值
     */
    private static final String SETTING_ENABLED_VALUE = "true";
    /**
     * 年度结算专用调度器Bean名
     */
    private static final String SETTLEMENT_SCHEDULER_BEAN = "stockAnnualSettlementScheduler";

    private final StockAnnualSettlementService settlementService;
    private final StockAnnualSettlementNoticeService noticeService;
    private final SysSettingManager sysSettingManager;
    private final StockMarketClock marketClock;
    private final ProjectProperty projectProperty;

    /**
     * 主入口:每年1月1日00:05结算上一自然年。
     * <p>
     * 00:05既在23:45桶轮次(00:00:10触发)之后,又在新年度第一个可成交桶(00:00桶→00:15:10轮次)之前。
     */
    @Scheduled(cron = "0 5 0 1 1 *", zone = "Asia/Shanghai", scheduler = SETTLEMENT_SCHEDULER_BEAN)
    public void settlePreviousYear() {
        settlePreviousYearIfEnabled();
    }

    /**
     * 窗口内补偿:00:07/00:09/00:11/00:13在门禁未通过时继续尝试;已结算时秒级幂等短路。
     */
    @Scheduled(cron = "0 7,9,11,13 0 1 1 *", zone = "Asia/Shanghai", scheduler = SETTLEMENT_SCHEDULER_BEAN)
    public void compensatePreviousYear() {
        settlePreviousYearIfEnabled();
    }

    /**
     * 启动补偿:仅在年度边界当日尝试,避免与轮次事务争用;出窗不自动补结历史年度。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        settlePreviousYearIfEnabled();
    }

    /**
     * 按开关与边界当日条件执行一次上一年度结算并投递年度报告。
     */
    private void settlePreviousYearIfEnabled() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }
        String enabled = sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ANNUAL_SETTLEMENT_ENABLED);
        if (!SETTING_ENABLED_VALUE.equalsIgnoreCase(enabled)) {
            log.debug("VIP股票年度结算-开关关闭,跳过");
            return;
        }
        LocalDateTime businessNow = marketClock.now();
        int settleYear = businessNow.getYear() - 1;
        LocalDate boundaryDate = LocalDate.of(settleYear + 1, 1, 1);
        if (!businessNow.toLocalDate().equals(boundaryDate)) {
            log.info("VIP股票年度结算-不在年度边界当日,跳过启动补偿且不自动补结: settleYear={}, businessDate={}",
                    settleYear, businessNow.toLocalDate());
            return;
        }
        try {
            StockAnnualSettlementService.AnnualSettlementOutcome outcome =
                    settlementService.settle(settleYear, businessNow);
            if (outcome == null) {
                return;
            }
            noticeService.sendAndUpdateNotice(outcome.notice(), outcome.reportText());
        } catch (Exception e) {
            log.error("VIP股票年度结算-执行异常,等待窗口内下一触发点重试: settleYear={}", settleYear, e);
        }
    }
}
