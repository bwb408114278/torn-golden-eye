package pn.torn.goldeneye.torn.service.faction.armory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.startup.StartupRecoveryDispatcher;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.faction.armory.FactionGiveFundsManager;
import pn.torn.goldeneye.torn.manager.faction.armory.FactionItemUsedManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Faction News 日采集补偿测试。
 *
 * @author Bai
 * @version 1.4.5
 * @since 2026.08.25
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Faction News日采集补偿测试")
class FactionNewsServiceTest {
    @Mock
    private DynamicTaskService taskService;
    @Mock
    private ThreadPoolTaskExecutor virtualThreadExecutor;
    @Mock
    private TornApiKeyConfig apiKeyConfig;
    @Mock
    private TornSettingFactionManager settingFactionManager;
    @Mock
    private FactionItemUsedManager itemUsedManager;
    @Mock
    private FactionGiveFundsManager giveFundsManager;
    @Mock
    private SysSettingDAO settingDao;
    @Mock
    private ProjectProperty projectProperty;
    @Mock
    private StartupRecoveryDispatcher recoveryDispatcher;
    @InjectMocks
    private FactionNewsService service;

    @BeforeEach
    void setUp() {
        lenient().when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
        lenient().doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(virtualThreadExecutor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("已完成今日新闻的启动仅注册明日任务")
    void init_whenTodayCompleted_shouldOnlyScheduleNextDay() {
        LocalDate today = LocalDate.now();
        when(settingDao.querySettingValue(SettingConstants.KEY_FACTION_NEWS_LOAD)).thenReturn(today.toString());

        service.init();

        verify(recoveryDispatcher, never()).submit(any());
        verify(taskService).updateTask(eq("faction-news-reload"), any(Runnable.class),
                eq(today.plusDays(1).atTime(8, 15)));
    }

    @Test
    @DisplayName("新闻任一帮派失败时不推进标记并保留原窗口同日重试")
    void collect_whenFactionFails_shouldRetrySameWindow() {
        LocalDate recordDate = LocalDate.now();
        TornSettingFactionDO faction = faction(1L);
        when(settingFactionManager.getList()).thenReturn(List.of(faction));
        when(apiKeyConfig.getFactionKey(1L, true)).thenReturn(key(1L));
        doThrow(new IllegalStateException("news failure"))
                .when(itemUsedManager).spiderItemUseData(eq(faction), any(), any());

        invokeSubmittedNews(recordDate.minusDays(1).atTime(8, 0),
                recordDate.atTime(7, 59, 59));

        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskService).updateTask(eq("faction-news-reload"), any(Runnable.class), timeCaptor.capture());
        assertEquals(recordDate, timeCaptor.getValue().toLocalDate());
        verify(settingDao, never()).updateSetting(eq(SettingConstants.KEY_FACTION_NEWS_LOAD), any());
        verify(itemUsedManager).spiderItemUseData(faction,
                recordDate.minusDays(1).atTime(8, 0), recordDate.atTime(7, 59, 59));
    }

    @Test
    @DisplayName("新闻全帮派成功后推进标记并仅注册明日任务")
    void collect_whenAllFactionsSucceed_shouldCompleteAndScheduleNextDay() {
        LocalDate recordDate = LocalDate.now();
        TornSettingFactionDO faction = faction(1L);
        when(settingFactionManager.getList()).thenReturn(List.of(faction));
        when(apiKeyConfig.getFactionKey(1L, true)).thenReturn(key(1L));

        invokeSubmittedNews(recordDate.atTime(8, 0), recordDate.plusDays(1).atTime(7, 59, 59));

        verify(settingDao).updateSetting(SettingConstants.KEY_FACTION_NEWS_LOAD,
                recordDate.plusDays(1).toString());
        verify(taskService).updateTask(eq("faction-news-reload"), any(Runnable.class),
                eq(recordDate.plusDays(2).atTime(8, 15)));
    }

    private void invokeSubmittedNews(LocalDateTime from, LocalDateTime to) {
        ArgumentCaptor<StartupRecoveryDispatcher.StartupRecoveryTask> captor =
                ArgumentCaptor.forClass(StartupRecoveryDispatcher.StartupRecoveryTask.class);
        service.spiderNewsData(from, to);
        verify(recoveryDispatcher).submit(captor.capture());
        captor.getValue().runnable().run();
    }

    private TornSettingFactionDO faction(long id) {
        TornSettingFactionDO faction = new TornSettingFactionDO();
        faction.setId(id);
        return faction;
    }

    private TornApiKeyDO key(long userId) {
        TornApiKeyDO key = new TornApiKeyDO();
        key.setId(userId);
        key.setUserId(userId);
        return key;
    }
}
