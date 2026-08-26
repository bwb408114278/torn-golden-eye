package pn.torn.goldeneye.torn.service.faction.armory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.startup.StartupRecoveryDispatcher;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.faction.armory.FactionGiveFundsManager;
import pn.torn.goldeneye.torn.manager.faction.armory.FactionItemUsedManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 帮派新闻记录逻辑类。
 *
 * @author Bai
 * @version 1.4.7
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_FACTION_NEWS)
@Slf4j
public class FactionNewsService {
    private static final int DAILY_HOUR = 8;
    private static final int DAILY_MINUTE = 15;
    private static final int NEWS_WINDOW_START_HOUR = 8;
    private static final int NEWS_WINDOW_END_HOUR = 7;
    private static final int NEWS_WINDOW_END_MINUTE = 59;
    private static final int NEWS_WINDOW_END_SECOND = 59;
    private static final long RETRY_MINUTES = 5L;
    private static final String TASK_ID = "faction-news-reload";
    private static final String RECOVERY_TASK_NAME = "faction-news-recovery";

    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornSettingFactionManager settingFactionManager;
    private final FactionItemUsedManager itemUsedManager;
    private final FactionGiveFundsManager giveFundsManager;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;
    private final StartupRecoveryDispatcher recoveryDispatcher;
    private final AtomicBoolean newsCollecting = new AtomicBoolean();

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        LocalDate recordDate = now().toLocalDate();
        LocalDate loadedDate = DateTimeUtils.convertToDate(
                settingDao.querySettingValue(SettingConstants.KEY_FACTION_NEWS_LOAD));
        if (loadedDate.isBefore(recordDate)) {
            NewsWindow window = new NewsWindow(loadedDate.atTime(NEWS_WINDOW_START_HOUR, 0),
                    recordDate.atTime(NEWS_WINDOW_END_HOUR, NEWS_WINDOW_END_MINUTE, NEWS_WINDOW_END_SECOND));
            submitNewsCollection(window, Trigger.APPLICATION_STARTUP_RECOVERY);
            return;
        }

        scheduleNextDailyRun(recordDate);
    }

    /**
     * 爬取指定新闻窗口。
     *
     * @param from 新闻窗口开始时间
     * @param to   新闻窗口结束时间
     */
    public void spiderNewsData(LocalDateTime from, LocalDateTime to) {
        submitNewsCollection(new NewsWindow(from, to), Trigger.DAILY_SCHEDULE);
    }

    /**
     * 投递指定新闻窗口的后台采集任务。
     *
     * @param window  新闻采集窗口
     * @param trigger 任务触发来源
     */
    private void submitNewsCollection(NewsWindow window, Trigger trigger) {
        LocalDateTime scheduledAt = now();
        log.info("Faction News日采集已受理, trigger={}, recordDate={}, scheduledAt={}, delayed={}",
                trigger, window.recordDate(), scheduledAt, scheduledAt.isAfter(window.to()));
        recoveryDispatcher.submit(new StartupRecoveryDispatcher.StartupRecoveryTask(
                RECOVERY_TASK_NAME,
                () -> collectNewsForRecordDate(window, trigger),
                () -> scheduleRetry(window, now().plusMinutes(RETRY_MINUTES))));
    }

    /**
     * 执行指定新闻窗口的全帮派采集并维护完成状态。单个帮派采集失败只记录日志, 不阻断批次完成与次日日程。
     *
     * @param window  新闻采集窗口
     * @param trigger 任务触发来源
     */
    private void collectNewsForRecordDate(NewsWindow window, Trigger trigger) {
        if (!newsCollecting.compareAndSet(false, true)) {
            LocalDateTime retryAt = now().plusMinutes(RETRY_MINUTES);
            log.warn("Faction News发生同JVM重入, trigger={}, recordDate={}, retryAt={}",
                    trigger, window.recordDate(), retryAt);
            scheduleRetry(window, retryAt);
            return;
        }

        List<TornSettingFactionDO> factionList = List.of();
        try {
            factionList = List.copyOf(settingFactionManager.getList());
            List<CompletableFuture<Boolean>> futures = factionList.stream()
                    .map(faction -> CompletableFuture.supplyAsync(
                            () -> collectFactionNews(faction, window, trigger),
                            virtualThreadExecutor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            long successCount = futures.stream().filter(CompletableFuture::join).count();

            settingDao.updateSetting(SettingConstants.KEY_FACTION_NEWS_LOAD,
                    DateTimeUtils.convertToString(window.recordDate()));
            LocalDateTime nextDailyRunAt = scheduleNextDailyRun(window.recordDate());
            log.info("Faction News日采集完成, trigger={}, recordDate={}, factionCount={}, successCount={}, completedAt={}, nextDailyRunAt={}",
                    trigger, window.recordDate(), factionList.size(), successCount, now(), nextDailyRunAt);
        } catch (Exception exception) {
            LocalDateTime retryAt = now().plusMinutes(RETRY_MINUTES);
            log.error("Faction News日采集异常, trigger={}, recordDate={}, factionCount={}, retryAt={}",
                    trigger, window.recordDate(), factionList.size(), retryAt, exception);
            scheduleRetry(window, retryAt);
        } finally {
            newsCollecting.set(false);
        }
    }

    /**
     * 采集单个帮派在指定窗口内的两类新闻数据。
     *
     * @param faction 帮派配置
     * @param window  新闻采集窗口
     * @param trigger 任务触发来源
     * @return 两类新闻均成功时返回 true
     */
    private boolean collectFactionNews(TornSettingFactionDO faction, NewsWindow window, Trigger trigger) {
        try {
            boolean itemUsedCompleted = itemUsedManager.spiderItemUseData(faction, window.from(), window.to());
            boolean giveFundsCompleted = giveFundsManager.spiderGiveFundsData(faction, window.from(), window.to());
            if (!itemUsedCompleted || !giveFundsCompleted) {
                log.warn("Faction News帮派采集未完成, trigger={}, recordDate={}, factionId={}",
                        trigger, window.recordDate(), faction.getId());
                return false;
            }
            return true;
        } catch (Exception exception) {
            log.error("Faction News帮派采集失败, trigger={}, recordDate={}, factionCount=1", trigger,
                    window.recordDate(), exception);
            return false;
        }
    }

    /**
     * 为原新闻窗口注册同日 retry。
     *
     * @param window  新闻采集窗口
     * @param retryAt 下一次 retry 的墙钟时间
     * @return 注册的 retry 时间
     */
    private LocalDateTime scheduleRetry(NewsWindow window, LocalDateTime retryAt) {
        log.info("Faction News retry已安排, recordDate={}, retryAt={}", window.recordDate(), retryAt);
        taskService.updateTask(TASK_ID,
                () -> submitNewsCollection(window, Trigger.RETRY), retryAt);
        return retryAt;
    }

    /**
     * 为已完成新闻日期注册次日 08:15 日常任务。
     *
     * @param completedRecordDate 已完成的新闻业务日期
     * @return 次日 08:15 的执行时间
     */
    private LocalDateTime scheduleNextDailyRun(LocalDate completedRecordDate) {
        LocalDate nextRecordDate = completedRecordDate.plusDays(1);
        LocalDateTime nextDailyRunAt = nextRecordDate.atTime(DAILY_HOUR, DAILY_MINUTE);
        taskService.updateTask(TASK_ID,
                () -> submitNewsCollection(new NewsWindow(
                                completedRecordDate.atTime(NEWS_WINDOW_START_HOUR, 0),
                                nextRecordDate.atTime(NEWS_WINDOW_END_HOUR, NEWS_WINDOW_END_MINUTE, NEWS_WINDOW_END_SECOND)),
                        Trigger.DAILY_SCHEDULE), nextDailyRunAt);
        return nextDailyRunAt;
    }

    LocalDateTime now() {
        return LocalDateTime.now();
    }

    /**
     * 不可变新闻采集时间窗口。
     *
     * @param from 窗口开始时间
     * @param to   窗口结束时间
     */
    private record NewsWindow(LocalDateTime from, LocalDateTime to) {
        /**
         * 获取窗口结束时间对应的业务日期。
         *
         * @return 新闻业务日期
         */
        private LocalDate recordDate() {
            return to.toLocalDate();
        }
    }

    private enum Trigger {
        APPLICATION_STARTUP_RECOVERY,
        DAILY_SCHEDULE,
        RETRY
    }
}
