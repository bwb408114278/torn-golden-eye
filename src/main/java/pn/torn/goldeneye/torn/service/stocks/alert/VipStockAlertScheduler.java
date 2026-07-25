package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VIP股票策略调度器 - 每分钟驱动数据轮次构建与启动补偿
 * <p>
 * 在生产环境下每分钟第20秒检查已结束但尚未完成的15分钟轮次,按round_time升序逐个处理:
 * 先构建bar,再构建特征,状态流转 PENDING -> BUILDING_BAR -> BUILDING_FEATURE -> COMPLETED;
 * 若bar构建结果为空(无采样数据)则标记为WAITING_DATA。
 * <p>
 * JVM内通过 {@link AtomicBoolean#compareAndSet(boolean, boolean)} 防重入,finally释放;
 * 数据库 {@code round_time} 部分唯一索引提供最终幂等,不引入Redis锁或ShedLock等新依赖。
 *
 * <h3>启动补偿</h3>
 * <ol>
 *   <li>验证VIP组合槽位完整性</li>
 *   <li>初始化当月风格/成熟度/风险草稿记录</li>
 *   <li>从最后已完成轮次之后重建历史bar与特征至当前已结束桶</li>
 *   <li>重建完成后调用 {@link #processPendingRounds()} 处理未完成轮次</li>
 * </ol>
 * 每个初始化步骤独立try-catch,单步失败仅记录日志不阻塞后续。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VipStockAlertScheduler {
    /**
     * 开关启用标识
     */
    private static final String SETTING_ENABLED_VALUE = "true";
    /**
     * 买入规则版本
     */
    public static final String BUY_RULE_VERSION = "1.0.0";
    /**
     * 卖出规则版本
     */
    public static final String SELL_RULE_VERSION = "1.0.0";
    /**
     * 仓位分配规则版本
     */
    public static final String ALLOCATION_RULE_VERSION = "1.0.0";
    /**
     * 消息通知规则版本
     */
    public static final String MESSAGE_RULE_VERSION = "1.0.0";

    private final Stock15mBarBuildService barBuildService;
    private final Stock15mFeatureBuildService featureBuildService;
    private final TornStockMarketRoundDAO roundDAO;
    private final StockHistoryRebuildService historyRebuildService;
    private final StockPortfolioInitService portfolioInitService;
    private final StockMonthlyStateInitService monthlyStateInitService;
    private final StockNoticeSendService noticeSendService;
    private final SysSettingManager sysSettingManager;
    private final ProjectProperty projectProperty;

    /**
     * JVM内防重入标记,同一时刻仅允许一个轮次处理流程
     */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * 每分钟第20秒执行轮次调度
     * <p>
     * 执行前置检查:
     * <ol>
     *   <li>非生产环境直接返回</li>
     *   <li> {@link SettingConstants#KEY_VIP_STOCK_ALERT_ENABLED} 开关不为 "true" 时返回</li>
     *   <li>{@link AtomicBoolean#compareAndSet(boolean, boolean)} 抢占防重入标记失败时返回</li>
     * </ol>
     * 通过后调用 {@link #processPendingRounds()} 处理已结束但未完成的轮次,finally释放标记。
     */
    @Scheduled(cron = "10 * * * * ?")
    public void executeRound() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String enabled = sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED);
        if (!SETTING_ENABLED_VALUE.equalsIgnoreCase(enabled)) {
            return;
        }

        if (!processing.compareAndSet(false, true)) {
            log.warn("VIP股票策略调度-上一轮处理尚未完成,跳过本次调度");
            return;
        }

        try {
            processPendingRounds();
            noticeSendService.sendPendingNotices();
        } finally {
            processing.set(false);
        }
    }

    /**
     * 处理已结束但尚未完成的轮次
     * <p>
     * 计算当前已结束的桶时间(当前桶对齐后回退15分钟),查询该时间之前全部未完成轮次并按
     * round_time升序逐个处理。每个轮次:
     * <ol>
     *   <li>状态置为BUILDING_BAR,调用 {@link Stock15mBarBuildService#buildBars(LocalDateTime)}</li>
     *   <li>bar构建为空时状态置为WAITING_DATA,跳过特征构建</li>
     *   <li>状态置为BUILDING_FEATURE,调用 {@link Stock15mFeatureBuildService#buildFeatures(LocalDateTime)}</li>
     *   <li>状态置为COMPLETED,记录completedAt</li>
     * </ol>
     * 每处理完一个轮次检查防重入标记是否仍持有,标记丢失时中断处理。
     * 单个轮次异常时记录错误并将状态置为FAILED_RETRYABLE,不中断后续轮次。
     */
    public void processPendingRounds() {
        LocalDateTime currentEndedBucket = Stock15mBarBuildService.alignToBucket(LocalDateTime.now())
                .minusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);

        List<TornStockMarketRoundDO> pendingRounds = roundDAO.selectPendingRoundsBefore(currentEndedBucket);
        if (CollectionUtils.isEmpty(pendingRounds)) {
            log.debug("VIP股票策略调度-无待处理轮次, currentEndedBucket={}", currentEndedBucket);
            return;
        }

        log.info("VIP股票策略调度-发现{}个待处理轮次, currentEndedBucket={}", pendingRounds.size(), currentEndedBucket);
        for (TornStockMarketRoundDO round : pendingRounds) {
            if (!processing.get()) {
                log.warn("VIP股票策略调度-防重入标记已释放,中断轮次处理");
                break;
            }

            LocalDateTime roundTime = round.getRoundTime();
            try {
                processSingleRound(round, roundTime);
            } catch (Exception e) {
                log.error("VIP股票策略调度-轮次处理异常, roundTime={}", roundTime, e);
                markFailed(round, e);
            }
        }
    }

    /**
     * 应用启动后执行补偿初始化
     * <p>
     * 非生产环境或开关关闭时直接返回。依次执行(每步独立try-catch,单步失败不阻塞后续):
     * <ol>
     *   <li> {@link StockPortfolioInitService#verifyAndInitSlots()} 验证VIP组合槽位</li>
     *   <li> {@link StockMonthlyStateInitService#initCurrentMonth()} 初始化月度风格草稿</li>
     *   <li> {@link StockHistoryRebuildService#rebuildFromLastCompleted(LocalDateTime)} 重建历史</li>
     *   <li> {@link #processPendingRounds()} 处理未完成轮次</li>
     * </ol>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String enabled = sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALERT_ENABLED);
        if (!SETTING_ENABLED_VALUE.equalsIgnoreCase(enabled)) {
            return;
        }

        log.info("VIP股票策略调度-启动补偿开始");

        try {
            portfolioInitService.verifyAndInitSlots();
        } catch (Exception e) {
            log.error("VIP股票策略调度-组合槽位验证失败,继续后续步骤", e);
        }

        try {
            monthlyStateInitService.initCurrentMonth();
        } catch (Exception e) {
            log.error("VIP股票策略调度-月度状态初始化失败,继续后续步骤", e);
        }

        try {
            LocalDateTime currentEndedBucket = Stock15mBarBuildService.alignToBucket(LocalDateTime.now())
                    .minusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
            historyRebuildService.rebuildFromLastCompleted(currentEndedBucket);
        } catch (Exception e) {
            log.error("VIP股票策略调度-历史重建失败,继续处理未完成轮次", e);
        }

        try {
            processPendingRounds();
        } catch (Exception e) {
            log.error("VIP股票策略调度-启动补偿处理未完成轮次失败", e);
        }

        log.info("VIP股票策略调度-启动补偿完成");
    }

    /**
     * 处理单个轮次: 构建bar -> 构建特征 -> 标记COMPLETED
     * <p>
     * bar构建结果为空时(无采样数据)将轮次标记为WAITING_DATA并返回,不继续构建特征。
     * 规则版本字段(buy/sell/allocation/message)在首次进入BUILDING_BAR时填充。
     *
     * @param round     待处理轮次记录
     * @param roundTime 轮次锚定的bar时间
     */
    private void processSingleRound(TornStockMarketRoundDO round, LocalDateTime roundTime) {
        log.info("VIP股票策略调度-开始处理轮次, roundTime={}", roundTime);

        round.setRoundStatus(StockRoundStatusEnum.BUILDING_BAR.getCode());
        round.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        round.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        round.setBuyRuleVersion(BUY_RULE_VERSION);
        round.setSellRuleVersion(SELL_RULE_VERSION);
        round.setAllocationRuleVersion(ALLOCATION_RULE_VERSION);
        round.setMessageRuleVersion(MESSAGE_RULE_VERSION);
        round.setStartedAt(LocalDateTime.now());
        roundDAO.updateById(round);

        List<TornStockMarketBar15mDO> bars = barBuildService.buildBars(roundTime);
        if (CollectionUtils.isEmpty(bars)) {
            log.warn("VIP股票策略调度-轮次bar构建为空, 标记WAITING_DATA, roundTime={}", roundTime);
            round.setRoundStatus(StockRoundStatusEnum.WAITING_DATA.getCode());
            round.setUsableStockCount(0);
            round.setCompletedAt(LocalDateTime.now());
            roundDAO.updateById(round);
            return;
        }

        round.setExpectedStockCount(bars.size());
        round.setRoundStatus(StockRoundStatusEnum.BUILDING_FEATURE.getCode());
        roundDAO.updateById(round);

        int featureCount = featureBuildService.buildFeatures(roundTime).size();
        round.setUsableStockCount(featureCount);
        round.setRoundStatus(StockRoundStatusEnum.COMPLETED.getCode());
        round.setCompletedAt(LocalDateTime.now());
        roundDAO.updateById(round);

        log.info("VIP股票策略调度-轮次处理完成, roundTime={}, 预期股票={}, 特征股票={}",
                roundTime, bars.size(), featureCount);
    }

    /**
     * 将轮次标记为可重试失败并记录错误信息
     *
     * @param round 待标记的轮次记录
     * @param e     触发失败的异常
     */
    private void markFailed(TornStockMarketRoundDO round, Exception e) {
        try {
            round.setRoundStatus(StockRoundStatusEnum.FAILED_RETRYABLE.getCode());
            round.setErrorMessage(e.getMessage());
            round.setCompletedAt(LocalDateTime.now());
            roundDAO.updateById(round);
        } catch (Exception updateEx) {
            log.error("VIP股票策略调度-标记轮次失败状态异常, roundTime={}", round.getRoundTime(), updateEx);
        }
    }
}
