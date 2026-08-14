package pn.torn.goldeneye.torn.service.stocks.backfill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.property.StockHistoryBackfillProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketClock;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 股票历史缺口回填调度器 - 启动短窗口恢复、每小时自动补正与 13 个月一次性实验入口
 * <p>
 * 与 VIP 轮次事务隔离，不更新 {@code torn_stocks}、不发送消息、不推进旧分钟特征游标。
 * 自动补正只修复最近 24 小时短缺口；最近 5 分钟不补，避免与实时 Torn 采集竞争。
 * 13 个月实验使用配置中的固定窗口，可中断重试（已插入分钟通过唯一索引跳过），
 * 不得以新「当前时刻 - 13 个月」重置边界。JVM 内通过 {@link AtomicBoolean} 防重入。
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.08.13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornsyStockHistoryBackfillScheduler {

    /**
     * 稳定截止：最近多少分钟不从 Tornsy 补（避免与实时采集竞争）
     */
    static final int STABLE_CUTOFF_MINUTES = 5;
    /**
     * 自动补正的最大缺口窗口（小时）
     */
    static final int AUTO_WINDOW_HOURS = 24;
    /**
     * 实验默认回看月数
     */
    static final int EXPERIMENT_MONTHS = 13;

    private final TornsyStockHistoryBackfillService backfillService;
    private final StockHistoryBackfillProperty property;
    private final StockMarketClock clock;
    private final ProjectProperty projectProperty;
    private final ThreadPoolTaskExecutor stockBackfillExecutor;

    /**
     * 自动补正防重入标记
     */
    private final AtomicBoolean autoProcessing = new AtomicBoolean(false);
    /**
     * 实验已完成标记（成功后关闭，避免同进程内重复执行）
     */
    private final AtomicBoolean experimentCompleted = new AtomicBoolean(false);

    /**
     * 应用启动后按需触发启动短窗口恢复与一次性实验（均异步执行）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!isProd()) {
            return;
        }
        if (property.isAutoEnabled()) {
            triggerStartupRecoveryAsync();
        }
        if (property.isExperimentEnabled()) {
            triggerExperimentAsync();
        }
    }

    /**
     * 每小时低峰自动补正最近 24 小时内的短缺口（派发到专用回填执行器,不占用调度线程）
     */
    @Scheduled(cron = "0 30 * * * ?", zone = "Asia/Shanghai")
    public void runHourlyCorrection() {
        if (!isProd() || !property.isAutoEnabled()) {
            return;
        }
        stockBackfillExecutor.execute(() -> autoBackfill(clock.now()));
    }

    /**
     * 异步触发启动短窗口恢复（使用专用回填执行器）
     */
    private void triggerStartupRecoveryAsync() {
        stockBackfillExecutor.execute(() -> {
            try {
                autoBackfill(clock.now());
            } catch (Exception e) {
                log.error("启动短窗口恢复失败: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 异步触发一次性实验（使用专用回填执行器）
     */
    private void triggerExperimentAsync() {
        stockBackfillExecutor.execute(() -> runExperimentOnce(clock.now()));
    }

    /**
     * 执行自动短窗口补正（最近 24 小时，排除最近 5 分钟），JVM 防重入
     *
     * @param now 当前业务时间（Asia/Shanghai）
     */
    void autoBackfill(LocalDateTime now) {
        if (!autoProcessing.compareAndSet(false, true)) {
            log.warn("历史回填-自动补正上一轮尚未完成, 跳过本次");
            return;
        }
        try {
            LocalDateTime stableEnd = floorToMinute(now.minusMinutes(STABLE_CUTOFF_MINUTES));
            LocalDateTime start = floorToMinute(now.minusHours(AUTO_WINDOW_HOURS));
            if (!start.isBefore(stableEnd)) {
                log.debug("历史回填-自动补正窗口为空, stableEnd={}", stableEnd);
                return;
            }
            log.info("历史回填-自动补正开始, 模式=AUTO, requestedStart={}, requestedEnd={}", start, stableEnd);
            backfillService.backfillRange(start, stableEnd);
        } finally {
            autoProcessing.set(false);
        }
    }

    /**
     * 执行一次性实验补数（固定窗口），成功后关闭实验开关
     *
     * @param now 当前业务时间（Asia/Shanghai）
     */
    void runExperimentOnce(LocalDateTime now) {
        if (experimentCompleted.get()) {
            log.debug("历史回填-实验已完成, 跳过重复执行");
            return;
        }
        ExperimentWindow window = resolveExperimentWindow(now);
        log.info("历史回填-实验开始, 模式=EXPERIMENT, requestedStart={}, requestedEnd={}",
                window.start(), window.end());
        try {
            backfillService.backfillRange(window.start(), window.end());
            experimentCompleted.set(true);
            log.info("历史回填-实验完成, 请关闭实验开关, requestedStart={}, requestedEnd={}",
                    window.start(), window.end());
        } catch (Exception e) {
            log.error("历史回填-实验失败(保持固定窗口, 重启后按缺口安全续跑), requestedStart={}, requestedEnd={}: {}",
                    window.start(), window.end(), e.getMessage(), e);
        }
    }

    /**
     * 解析实验固定窗口
     * <p>
     * 优先使用配置中的固定开始/结束时间（重试不漂移）；配置缺失时按「执行时刻前推 13 个月
     * 至稳定截止」计算。固定结束时间必须早于稳定截止时间。
     *
     * @param now 当前业务时间（Asia/Shanghai）
     * @return 实验窗口
     */
    ExperimentWindow resolveExperimentWindow(LocalDateTime now) {
        LocalDateTime end = floorToMinute(now.minusMinutes(STABLE_CUTOFF_MINUTES));
        LocalDateTime configuredStart = parseTime(property.getExperimentStart());
        LocalDateTime configuredEnd = parseTime(property.getExperimentEnd());
        if (configuredStart != null && configuredEnd != null) {
            if (!configuredEnd.isBefore(end)) {
                throw new BizException("实验结束时间必须早于稳定截止时间, configuredEnd=" + configuredEnd);
            }
            return new ExperimentWindow(configuredStart, configuredEnd);
        }
        LocalDateTime start = floorToMinute(end.minusMonths(EXPERIMENT_MONTHS));
        return new ExperimentWindow(start, end);
    }

    /**
     * 将时间向下截断到分钟（秒与纳秒清零）
     */
    private LocalDateTime floorToMinute(LocalDateTime time) {
        return time.withSecond(0).withNano(0);
    }

    /**
     * 解析固定时间字符串，为空或非法时返回 null
     */
    private LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return DateTimeUtils.convertToDateTime(text.trim());
        } catch (Exception e) {
            log.warn("历史回填-实验固定时间解析失败, 输入={}: {}", text, e.getMessage());
            return null;
        }
    }

    /**
     * 是否生产环境
     */
    private boolean isProd() {
        return BotConstants.ENV_PROD.equals(projectProperty.getEnv());
    }

    /**
     * 实验固定窗口
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     */
    record ExperimentWindow(LocalDateTime start, LocalDateTime end) {
    }
}
