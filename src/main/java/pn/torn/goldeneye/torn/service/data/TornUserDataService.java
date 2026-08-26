package pn.torn.goldeneye.torn.service.data;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.startup.StartupRecoveryDispatcher;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.napcat.receive.member.GroupMemberDataRec;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserBsSnapshotDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserBsSnapshotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcUserManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.user.TornQqUserManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;
import pn.torn.goldeneye.torn.model.user.bs.TornUserBsDTO;
import pn.torn.goldeneye.torn.model.user.bs.TornUserBsVO;
import pn.torn.goldeneye.torn.model.user.oc.TornUserOcDTO;
import pn.torn.goldeneye.torn.model.user.oc.TornUserOcVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Torn 用户数据逻辑层。
 *
 * @author Bai
 * @version 1.4.7
 * @since 2025.08.20
 */
@Service
@RequiredArgsConstructor
@Order(InitOrderConstants.TORN_USER_DATA)
@Slf4j
public class TornUserDataService {
    private static final int DAILY_HOUR = 8;
    private static final int DAILY_MINUTE = 5;
    private static final long RETRY_MINUTES = 5L;
    private static final String TASK_ID = "user-data-reload";
    private static final String RECOVERY_TASK_NAME = "user-data-recovery";

    private final DynamicTaskService taskService;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final TornSettingFactionManager settingFactionManager;
    private final TornSettingFactionDAO settingFactionDao;
    private final TornUserManager userManager;
    private final TornQqUserManager qqUserManager;
    private final TornFactionOcUserManager ocUserManager;
    private final TornUserDAO userDao;
    private final TornUserBsSnapshotDAO bsSnapshotDao;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;
    private final StartupRecoveryDispatcher recoveryDispatcher;
    private final AtomicBoolean bsCollecting = new AtomicBoolean();

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        LocalDate recordDate = now().toLocalDate();
        LocalDate loadedDate = DateTimeUtils.convertToDate(
                settingDao.querySettingValue(SettingConstants.KEY_USER_DATA_LOAD));
        if (loadedDate.isBefore(recordDate)) {
            submitDailyOrStartupCollection(recordDate, Trigger.APPLICATION_STARTUP_RECOVERY);
            return;
        }

        scheduleNextDailyRun(recordDate);
    }

    /**
     * 提交指定业务日期的用户 BS 日采集任务。
     *
     * @param recordDate 需要完成的业务日期
     * @param trigger    任务触发来源
     */
    void submitDailyOrStartupCollection(LocalDate recordDate, Trigger trigger) {
        LocalDateTime scheduledAt = now();
        boolean delayed = scheduledAt.isAfter(recordDate.atTime(DAILY_HOUR, DAILY_MINUTE));
        log.info("BS日采集已受理, trigger={}, recordDate={}, scheduledAt={}, delayed={}",
                trigger, recordDate, scheduledAt, delayed);
        recoveryDispatcher.submit(new StartupRecoveryDispatcher.StartupRecoveryTask(
                RECOVERY_TASK_NAME,
                () -> collectBsForRecordDate(recordDate, trigger),
                () -> scheduleRetry(recordDate, now().plusMinutes(RETRY_MINUTES))));
    }

    /**
     * 爬取所有用户数据。保留该公开入口供绑定 Key 流程使用。
     *
     * @param to 兼容历史调用的业务时间
     */
    public void spiderAllData(LocalDateTime to) {
        submitDailyOrStartupCollection(to.toLocalDate(), Trigger.DAILY_SCHEDULE);
    }

    /**
     * 爬取单条用户数据。该入口不参与日批完成标记判定。
     *
     * @param key          用户 Key
     * @param snapshotList 已存在快照列表
     */
    public void spiderData(TornApiKeyDO key, List<TornUserBsSnapshotDO> snapshotList) {
        updateBsSnapshot(key, now().toLocalDate(), snapshotList);
        updateOcRate(key);
    }

    /**
     * 绑定用户和 QQ。
     */
    public void bindUserAndQq() {
        for (TornSettingFactionDO faction : settingFactionManager.getList()) {
            if (faction.getGroupId().equals(0L)) {
                continue;
            }

            List<GroupMemberDataRec> memberList = qqUserManager.getGroupMemberList(faction.getGroupId());
            List<TornUserDO> userList = userDao.lambdaQuery().eq(TornUserDO::getQqId, 0L).list();

            for (TornUserDO user : userList) {
                String card = "[" + user.getId() + "]";
                memberList.stream()
                        .filter(m -> m.getCard().contains(card))
                        .findAny()
                        .ifPresent(member -> userDao.lambdaUpdate()
                                .set(TornUserDO::getQqId, member.getUserId())
                                .eq(TornUserDO::getId, user.getId())
                                .update());
            }

            List<String> adminIdList = memberList.stream()
                    .filter(m -> "owner".equals(m.getRole()) || "admin".equals(m.getRole()))
                    .map(m -> String.valueOf(m.getUserId()))
                    .toList();
            settingFactionDao.lambdaUpdate()
                    .set(TornSettingFactionDO::getAllAdminQq, String.join(",", adminIdList))
                    .eq(TornSettingFactionDO::getId, faction.getId())
                    .update();
        }

        settingFactionManager.refreshCache();
    }

    /**
     * 执行指定业务日期的 BS 批次，并在成功或失败后安排对应日程。单用户采集失败只记录日志, 不阻断批次完成与次日日程。
     *
     * @param recordDate 需要完成的业务日期
     * @param trigger    任务触发来源
     */
    private void collectBsForRecordDate(LocalDate recordDate, Trigger trigger) {
        if (!bsCollecting.compareAndSet(false, true)) {
            LocalDateTime retryAt = now().plusMinutes(RETRY_MINUTES);
            log.warn("BS日采集发生同JVM重入, trigger={}, recordDate={}, retryAt={}", trigger, recordDate, retryAt);
            scheduleRetry(recordDate, retryAt);
            return;
        }

        List<TornApiKeyDO> keyList = List.of();
        LocalDateTime collectedAt = now();
        try {
            keyList = apiKeyConfig.getAllEnableKeys();
            if (keyList.isEmpty()) {
                throw new IllegalStateException("无启用Torn Api Key");
            }
            Set<Long> expectedUserIds = keyList.stream().map(TornApiKeyDO::getUserId).collect(java.util.stream.Collectors.toSet());
            List<TornUserBsSnapshotDO> existingSnapshots = querySnapshots(recordDate);
            long existingSnapshotCount = countSnapshots(existingSnapshots, expectedUserIds);
            log.info("BS日采集开始, trigger={}, recordDate={}, expectedUserCount={}, existingSnapshotCount={}, collectedAt={}",
                    trigger, recordDate, expectedUserIds.size(), existingSnapshotCount, collectedAt);

            Map<Long, TornApiKeyDO> missingKeys = keyList.stream()
                    .filter(key -> !containsUser(existingSnapshots, key.getUserId(), recordDate))
                    .collect(java.util.stream.Collectors.toMap(TornApiKeyDO::getUserId, key -> key, (left, right) -> left));
            List<CompletableFuture<Void>> futures = missingKeys.values().stream()
                    .map(key -> CompletableFuture.runAsync(
                            () -> collectUserBsSnapshot(key, recordDate, existingSnapshots, trigger), virtualThreadExecutor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<TornUserBsSnapshotDO> finalSnapshots = querySnapshots(recordDate);
            long presentSnapshotCount = countSnapshots(finalSnapshots, expectedUserIds);

            userManager.refreshCache();
            settingDao.updateSetting(SettingConstants.KEY_USER_DATA_LOAD, DateTimeUtils.convertToString(recordDate));
            LocalDateTime nextDailyRunAt = scheduleNextDailyRun(recordDate);
            log.info("BS日采集完成, trigger={}, recordDate={}, expectedUserCount={}, presentSnapshotCount={}, completedAt={}, durationMs={}, nextDailyRunAt={}",
                    trigger, recordDate, expectedUserIds.size(), presentSnapshotCount, now(), elapsedMillis(collectedAt), nextDailyRunAt);
        } catch (Exception exception) {
            LocalDateTime retryAt = now().plusMinutes(RETRY_MINUTES);
            FailureSnapshot failureSnapshot = buildFailureSnapshot(recordDate, keyList);
            log.error("BS日采集异常, trigger={}, recordDate={}, expectedUserCount={}, presentSnapshotCount={}, missingUserCount={}, retryAt={}",
                    trigger, recordDate, keyList.size(), failureSnapshot.presentSnapshotCount(),
                    failureSnapshot.missingUserCount(), retryAt, exception);
            scheduleRetry(recordDate, retryAt);
        } finally {
            submitAncillaryRefreshes(keyList, trigger);
            bsCollecting.set(false);
        }
    }

    /**
     * 查询指定业务日期的全部 BS 快照。
     *
     * @param recordDate 业务日期
     * @return 指定日期的 BS 快照列表
     */
    private List<TornUserBsSnapshotDO> querySnapshots(LocalDate recordDate) {
        return bsSnapshotDao.lambdaQuery().eq(TornUserBsSnapshotDO::getRecordDate, recordDate).list();
    }

    /**
     * 查询指定日期的 BS 快照；查询失败时返回空列表以保证失败日志能够输出。
     *
     * @param recordDate 业务日期
     * @return 查询到的 BS 快照列表，查询失败时为空列表
     */
    private List<TornUserBsSnapshotDO> querySnapshotsSafely(LocalDate recordDate) {
        try {
            return querySnapshots(recordDate);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * 汇总失败批次的快照数量，避免失败日志重复查询数据库。
     *
     * @param recordDate 业务日期
     * @param keyList    当前批次冻结的 Key 集合
     * @return 失败批次的快照统计
     */
    private FailureSnapshot buildFailureSnapshot(LocalDate recordDate, List<TornApiKeyDO> keyList) {
        if (keyList.isEmpty()) {
            return new FailureSnapshot(0L, 0L);
        }
        Set<Long> expectedUserIds = keyList.stream()
                .map(TornApiKeyDO::getUserId)
                .collect(java.util.stream.Collectors.toSet());
        long presentSnapshotCount = countSnapshots(querySnapshotsSafely(recordDate), expectedUserIds);
        return new FailureSnapshot(presentSnapshotCount,
                Math.max(0L, expectedUserIds.size() - presentSnapshotCount));
    }

    /**
     * 统计目标用户集合中已经存在的不同 BS 快照用户数。
     *
     * @param snapshots       已查询的快照列表
     * @param expectedUserIds 本批次权威用户集合
     * @return 已存在快照的目标用户数
     */
    private long countSnapshots(List<TornUserBsSnapshotDO> snapshots, Set<Long> expectedUserIds) {
        return snapshots.stream().map(TornUserBsSnapshotDO::getUserId).filter(expectedUserIds::contains).distinct().count();
    }

    /**
     * 判断指定用户是否已经存在指定业务日期的快照。
     *
     * @param snapshots  已查询的快照列表
     * @param userId     用户ID
     * @param recordDate 业务日期
     * @return 存在匹配快照时返回 true
     */
    private boolean containsUser(List<TornUserBsSnapshotDO> snapshots, Long userId, LocalDate recordDate) {
        return snapshots.stream().anyMatch(snapshot -> userId.equals(snapshot.getUserId())
                && recordDate.equals(snapshot.getRecordDate()));
    }

    /**
     * 采集单个用户的 BS 快照; 单用户失败只记录日志, 不阻断批次完成与次日日程。
     *
     * @param key          用户 API Key
     * @param recordDate   业务日期
     * @param snapshotList 当前批次已存在的快照列表
     * @param trigger      任务触发来源
     */
    private void collectUserBsSnapshot(TornApiKeyDO key, LocalDate recordDate,
                                       List<TornUserBsSnapshotDO> snapshotList, Trigger trigger) {
        try {
            updateBsSnapshot(key, recordDate, snapshotList);
        } catch (Exception exception) {
            log.error("BS用户快照采集失败, trigger={}, userId={}, recordDate={}",
                    trigger, key.getUserId(), recordDate, exception);
        }
    }

    /**
     * 为缺失用户请求并保存指定业务日期的 BS 快照。
     *
     * @param key          用户 API Key
     * @param recordDate   业务日期
     * @param snapshotList 当前批次已存在的快照列表
     */
    private void updateBsSnapshot(TornApiKeyDO key, LocalDate recordDate, List<TornUserBsSnapshotDO> snapshotList) {
        if (containsUser(snapshotList, key.getUserId(), recordDate)) {
            return;
        }

        TornUserBsVO bs = tornApi.sendRequest(new TornUserBsDTO(), key, TornUserBsVO.class);
        if (bs == null || bs.getBattleStats() == null) {
            throw new IllegalStateException("BS响应为空");
        }
        bsSnapshotDao.save(bs.getBattleStats().convert2DO(key.getUserId(), recordDate));
    }

    /**
     * 后台投递 OC 成功率刷新和 QQ 绑定维护，隔离其对 BS 完成结论的影响。
     *
     * @param keyList 当前 BS 批次使用的 Key 集合
     * @param trigger 任务触发来源
     */
    private void submitAncillaryRefreshes(List<TornApiKeyDO> keyList, Trigger trigger) {
        if (keyList.isEmpty()) {
            return;
        }
        try {
            virtualThreadExecutor.execute(() -> {
                for (TornApiKeyDO key : keyList) {
                    try {
                        updateOcRate(key);
                    } catch (Exception exception) {
                        log.error("用户OC成功率刷新失败, trigger={}, userCount=1, bsCompletionUnaffected=true",
                                trigger, exception);
                    }
                }
                try {
                    bindUserAndQq();
                } catch (Exception exception) {
                    log.error("QQ用户绑定维护失败, trigger={}, factionScope=all, bsCompletionUnaffected=true",
                            trigger, exception);
                }
            });
        } catch (Exception exception) {
            log.error("BS外围维护投递失败, trigger={}, bsCompletionUnaffected=true", trigger, exception);
        }
    }

    /**
     * 使用指定 Key 刷新用户 OC 成功率。
     *
     * @param key 用户 API Key
     */
    private void updateOcRate(TornApiKeyDO key) {
        TornUserOcVO oc = tornApi.sendRequest(new TornUserOcDTO(), key, TornUserOcVO.class);
        if (oc == null || CollectionUtils.isEmpty(oc.getOcList())) {
            return;
        }
        List<TornFactionCrimeVO> ocList = oc.getOcList();
        ocUserManager.updateEmptyUserPassRate(key.getFactionId(), key.getUserId(), ocList);
    }

    /**
     * 为同一业务日期注册下一次 retry，禁止推进为次日日程。
     *
     * @param recordDate 业务日期
     * @param retryAt    下一次 retry 的墙钟时间
     * @return 注册的 retry 时间
     */
    private LocalDateTime scheduleRetry(LocalDate recordDate, LocalDateTime retryAt) {
        log.info("BS日采集retry已安排, recordDate={}, retryAt={}", recordDate, retryAt);
        taskService.updateTask(TASK_ID,
                () -> submitDailyOrStartupCollection(recordDate, Trigger.RETRY), retryAt);
        return retryAt;
    }

    /**
     * 为已完成的业务日期注册次日 08:05 日常任务。
     *
     * @param completedRecordDate 已完成的业务日期
     * @return 次日 08:05 的执行时间
     */
    private LocalDateTime scheduleNextDailyRun(LocalDate completedRecordDate) {
        LocalDate nextRecordDate = completedRecordDate.plusDays(1);
        LocalDateTime nextDailyRunAt = nextRecordDate.atTime(DAILY_HOUR, DAILY_MINUTE);
        taskService.updateTask(TASK_ID,
                () -> submitDailyOrStartupCollection(nextRecordDate, Trigger.DAILY_SCHEDULE), nextDailyRunAt);
        return nextDailyRunAt;
    }

    LocalDateTime now() {
        return LocalDateTime.now();
    }

    /**
     * 计算从指定采集开始时间到当前时间经过的毫秒数。
     *
     * @param startedAt 采集开始时间
     * @return 已经过的毫秒数
     */
    private long elapsedMillis(LocalDateTime startedAt) {
        return Math.max(0L, java.time.Duration.between(startedAt, now()).toMillis());
    }

    private enum Trigger {
        APPLICATION_STARTUP_RECOVERY,
        DAILY_SCHEDULE,
        RETRY
    }

    /**
     * BS 失败批次的快照统计。
     *
     * @param presentSnapshotCount 已存在的目标用户快照数
     * @param missingUserCount     缺失快照的目标用户数
     */
    private record FailureSnapshot(long presentSnapshotCount, long missingUserCount) {
    }
}
