package pn.torn.goldeneye.torn.service.data;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.startup.StartupRecoveryDispatcher;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserBsSnapshotDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.user.TornUserBsSnapshotDO;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcUserManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.user.TornQqUserManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.user.bs.TornUserBsDTO;
import pn.torn.goldeneye.torn.model.user.oc.TornUserOcVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 用户 BS 日采集补偿测试。
 *
 * @author Bai
 * @version 1.4.5
 * @since 2026.08.25
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户BS日采集补偿测试")
class TornUserDataServiceTest {
    @Mock
    private DynamicTaskService taskService;
    @Mock
    private ThreadPoolTaskExecutor virtualThreadExecutor;
    @Mock
    private TornApi tornApi;
    @Mock
    private TornApiKeyConfig apiKeyConfig;
    @Mock
    private TornSettingFactionManager settingFactionManager;
    @Mock
    private TornSettingFactionDAO settingFactionDao;
    @Mock
    private TornUserManager userManager;
    @Mock
    private TornQqUserManager qqUserManager;
    @Mock
    private TornFactionOcUserManager ocUserManager;
    @Mock
    private TornUserDAO userDao;
    @Mock
    private TornUserBsSnapshotDAO bsSnapshotDao;
    @Mock
    private SysSettingDAO settingDao;
    @Mock
    private ProjectProperty projectProperty;
    @Mock
    private StartupRecoveryDispatcher recoveryDispatcher;
    @Mock
    private LambdaQueryChainWrapper<TornUserBsSnapshotDO> snapshotQuery;
    @InjectMocks
    private TornUserDataService service;

    @BeforeEach
    void setUp() {
        lenient().when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        lenient().when(bsSnapshotDao.lambdaQuery()).thenReturn(snapshotQuery);
        lenient().when(snapshotQuery.eq(any(), any())).thenReturn(snapshotQuery);
        lenient().doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(virtualThreadExecutor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("已完成今日BS的启动仅注册明日任务")
    void init_whenTodayCompleted_shouldOnlyScheduleNextDay() {
        LocalDate today = LocalDate.now();
        when(settingDao.querySettingValue(SettingConstants.KEY_USER_DATA_LOAD)).thenReturn(today.toString());

        service.init();

        verify(recoveryDispatcher, never()).submit(any());
        verify(taskService).updateTask(eq("user-data-reload"), any(Runnable.class),
                eq(today.plusDays(1).atTime(8, 5)));
    }

    @Test
    @DisplayName("启动发现今日BS欠账时仅通过dispatcher投递后台任务")
    void init_whenTodayMissing_shouldDispatchWithoutRunningApiOnListenerThread() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(settingDao.querySettingValue(SettingConstants.KEY_USER_DATA_LOAD)).thenReturn(yesterday.toString());

        service.init();

        verify(recoveryDispatcher).submit(any(StartupRecoveryDispatcher.StartupRecoveryTask.class));
        verify(taskService, never()).updateTask(any(), any(Runnable.class), any(LocalDateTime.class));
        verify(tornApi, never()).sendRequest(any(TornUserBsDTO.class), any(TornApiKeyDO.class), any());
    }

    @Test
    @DisplayName("BS全量成功后更新完成标记并仅注册明日任务")
    void collect_whenAllSnapshotsExist_shouldCompleteAndScheduleNextDay() {
        LocalDate recordDate = LocalDate.now();
        TornApiKeyDO first = key(1L);
        TornApiKeyDO second = key(2L);
        when(apiKeyConfig.getAllEnableKeys()).thenReturn(List.of(first, second));
        when(snapshotQuery.list()).thenReturn(List.of(snapshot(1L, recordDate), snapshot(2L, recordDate)));

        invokeSubmittedTask(recordDate);

        verify(settingDao).updateSetting(SettingConstants.KEY_USER_DATA_LOAD, recordDate.toString());
        verify(taskService).updateTask(eq("user-data-reload"), any(Runnable.class),
                eq(recordDate.plusDays(1).atTime(8, 5)));
        verify(tornApi, never()).sendRequest(any(TornUserBsDTO.class), any(TornApiKeyDO.class), any());
    }

    @Test
    @DisplayName("单个BS请求失败后安排当天五分钟重试并保留原日期")
    void collect_whenBsRequestFails_shouldRetrySameRecordDate() {
        LocalDate recordDate = LocalDate.now();
        TornApiKeyDO key = key(1L);
        when(apiKeyConfig.getAllEnableKeys()).thenReturn(List.of(key));
        when(snapshotQuery.list()).thenReturn(List.of());
        doThrow(new IllegalStateException("test api failure"))
                .when(tornApi).sendRequest(any(TornReqParamV2.class), eq(key), any());
        LocalDateTime before = LocalDateTime.now().plusMinutes(5);

        invokeSubmittedTask(recordDate);

        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskService).updateTask(eq("user-data-reload"), any(Runnable.class), timeCaptor.capture());
        LocalDateTime retryAt = timeCaptor.getValue();
        assertEquals(recordDate, retryAt.toLocalDate());
        org.junit.jupiter.api.Assertions.assertTrue(!retryAt.isBefore(before.minusSeconds(2)));
        verify(settingDao, never()).updateSetting(eq(SettingConstants.KEY_USER_DATA_LOAD), any());
    }

    @Test
    @DisplayName("BS成功但OC刷新失败仍推进BS完成标记")
    void collect_whenOcRefreshFails_shouldStillCompleteBs() {
        LocalDate recordDate = LocalDate.now();
        TornApiKeyDO key = key(1L);
        when(apiKeyConfig.getAllEnableKeys()).thenReturn(List.of(key));
        when(snapshotQuery.list()).thenReturn(List.of(snapshot(1L, recordDate)));
        doThrow(new IllegalStateException("oc failure"))
                .when(tornApi).sendRequest(any(TornReqParamV2.class), eq(key), eq(TornUserOcVO.class));

        invokeSubmittedTask(recordDate);

        verify(settingDao).updateSetting(SettingConstants.KEY_USER_DATA_LOAD, recordDate.toString());
        verify(taskService).updateTask(eq("user-data-reload"), any(Runnable.class),
                eq(recordDate.plusDays(1).atTime(8, 5)));
    }

    @Test
    @DisplayName("BS成功但QQ绑定失败仍推进BS完成标记")
    void collect_whenQqBindingFails_shouldStillCompleteBs() {
        LocalDate recordDate = LocalDate.now();
        TornApiKeyDO key = key(1L);
        when(apiKeyConfig.getAllEnableKeys()).thenReturn(List.of(key));
        when(snapshotQuery.list()).thenReturn(List.of(snapshot(1L, recordDate)));
        when(settingFactionManager.getList()).thenThrow(new IllegalStateException("qq failure"));

        invokeSubmittedTask(recordDate);

        verify(settingDao).updateSetting(SettingConstants.KEY_USER_DATA_LOAD, recordDate.toString());
        verify(taskService).updateTask(eq("user-data-reload"), any(Runnable.class),
                eq(recordDate.plusDays(1).atTime(8, 5)));
    }

    private void invokeSubmittedTask(LocalDate recordDate) {
        ArgumentCaptor<StartupRecoveryDispatcher.StartupRecoveryTask> captor =
                ArgumentCaptor.forClass(StartupRecoveryDispatcher.StartupRecoveryTask.class);
        service.spiderAllData(recordDate.atStartOfDay());
        verify(recoveryDispatcher).submit(captor.capture());
        captor.getValue().runnable().run();
    }

    private TornApiKeyDO key(long userId) {
        TornApiKeyDO key = new TornApiKeyDO();
        key.setUserId(userId);
        key.setFactionId(1L);
        return key;
    }

    private TornUserBsSnapshotDO snapshot(long userId, LocalDate recordDate) {
        TornUserBsSnapshotDO snapshot = new TornUserBsSnapshotDO();
        snapshot.setUserId(userId);
        snapshot.setRecordDate(recordDate);
        return snapshot;
    }
}
