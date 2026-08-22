package pn.torn.goldeneye.torn.service.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.base.torn.TornReqParam;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.torn.TornItemHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.TornItemsDAO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemHistoryManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemTrendManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.model.torn.bank.TornBankDTO;
import pn.torn.goldeneye.torn.model.torn.bank.TornBankRateVO;
import pn.torn.goldeneye.torn.model.torn.bank.TornBankVO;
import pn.torn.goldeneye.torn.model.torn.stats.TornStatsVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Torn基础数据失败重试测试。
 *
 * @author Bai
 * @version 1.4.0
 * @since 2025.08.22
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Torn基础数据失败重试测试")
class TornBaseDataServiceRetryTest {
    @Mock
    private DynamicTaskService taskService;
    @Mock
    private ThreadPoolTaskExecutor virtualThreadExecutor;
    @Mock
    private TornApi tornApi;
    @Mock
    private SysSettingManager settingManager;
    @Mock
    private TornItemsManager itemsManager;
    @Mock
    private TornItemHistoryManager itemHistoryManager;
    @Mock
    private TornItemTrendManager itemTrendManager;
    @Mock
    private TornItemsDAO itemsDao;
    @Mock
    private TornItemHistoryDAO itemHistoryDao;
    @Mock
    private SysSettingDAO settingDao;
    @Mock
    private ProjectProperty projectProperty;

    @InjectMocks
    private TornBaseDataService tornBaseDataService;

    @Test
    @DisplayName("Point价格接口失败后应安排五分钟重试且不更新成功状态")
    void spiderBaseData_whenPointValueApiFails_shouldScheduleRetryInFiveMinutes() {
        TornBankRateVO bankRate = new TornBankRateVO();
        bankRate.setDays(90);
        bankRate.setRate(BigDecimal.ONE);
        TornBankVO bank = new TornBankVO();
        bank.setBank(List.of(bankRate));
        when(tornApi.sendRequest(any(TornBankDTO.class), eq(TornBankVO.class))).thenReturn(bank);
        when(tornApi.sendRequest(any(TornReqParam.class), eq(TornStatsVO.class)))
                .thenThrow(new IllegalStateException("test api failure"));
        LocalDateTime before = LocalDateTime.now();

        tornBaseDataService.spiderBaseData();

        LocalDateTime after = LocalDateTime.now();
        ArgumentCaptor<LocalDateTime> executionTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskService).updateTask(eq("base-data-reload"), any(Runnable.class), executionTimeCaptor.capture());
        LocalDateTime retryTime = executionTimeCaptor.getValue();
        assertTrue(!retryTime.isBefore(before.plusMinutes(5))
                        && !retryTime.isAfter(after.plusMinutes(5)),
                "失败任务应安排在当前时间约五分钟后重试");
        verify(tornApi, times(1)).sendRequest(any(TornReqParam.class), eq(TornStatsVO.class));
        verify(taskService, times(1)).updateTask(eq("base-data-reload"), any(Runnable.class), any(LocalDateTime.class));
        verify(settingDao, never()).updateSetting(any(), any());
        verify(itemsDao, never()).list();
        verify(itemsDao, never()).saveBatch(any());
        verify(itemsDao, never()).updateBatchById(any());
        verify(itemHistoryManager, never()).saveItemHistory(any());
        verify(itemTrendManager, never()).sendTrendMsg();
    }
}
