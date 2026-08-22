package pn.torn.goldeneye.torn.service.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.torn.TornItemsDAO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.torn.manager.torn.TornItemTrendManager;
import pn.torn.goldeneye.torn.model.torn.bank.TornBankDTO;
import pn.torn.goldeneye.torn.model.torn.bank.TornBankRateVO;
import pn.torn.goldeneye.torn.model.torn.bank.TornBankVO;
import pn.torn.goldeneye.torn.model.torn.items.TornItemsDTO;
import pn.torn.goldeneye.torn.model.torn.items.TornItemsListVO;
import pn.torn.goldeneye.torn.model.torn.stats.TornStatsDTO;
import pn.torn.goldeneye.torn.model.torn.stats.TornStatsVO;
import pn.torn.goldeneye.utils.JsonUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Torn基础数据测试。
 *
 * @author Bai
 * @version 1.4.0
 * @since 2025.08.22
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("爬取基础数据测试")
class TornBaseDataServiceTest {
    @Autowired
    private TornBaseDataService tornBaseDataService;
    @Autowired
    private TornApi tornApi;
    @Autowired
    private SysSettingDAO settingDao;
    @Autowired
    private TornItemsDAO itemsDao;
    // 以下依赖存在副作用（定时任务调度、异步执行、外发消息），mock 掉
    @MockitoBean
    private DynamicTaskService taskService;
    @MockitoBean
    private TornItemTrendManager itemTrendManager;
    @MockitoBean
    private ThreadPoolTaskExecutor virtualThreadExecutor;
    // mock 掉以防止 @EventListener init() 在上下文启动时真正执行爬取逻辑
    // projectProperty.getEnv() 默认返回 null，不等于 ENV_PROD，init() 会直接 return
    @MockitoBean
    private ProjectProperty projectProperty;

    @Test
    @DisplayName("爬取银行利率")
    void spiderBankRate_shouldFetchAndPersistBankRate() {
        tornBaseDataService.spiderBankRate();

        String bankRate = settingDao.querySettingValue(SettingConstants.KEY_BANK_RATE);
        assertNotNull(bankRate, "银行利率不应为空");
        assertDoesNotThrow(() -> new BigDecimal(bankRate), "银行利率应为合法数字");
        assertTrue(new BigDecimal(bankRate).compareTo(BigDecimal.ZERO) > 0, "银行利率应大于0");
    }

    @Test
    @DisplayName("爬取Point价格")
    void spiderPointValue_shouldFetchAndPersistPointValue() {
        tornBaseDataService.spiderPointValue();

        String pointValue = settingDao.querySettingValue(SettingConstants.KEY_POINT_VALUE);
        assertNotNull(pointValue, "PT价值不应为空");
        long value = assertDoesNotThrow(() -> Long.parseLong(pointValue), "PT价值应为合法整数");
        assertTrue(value > 0, "PT价值应大于0");
    }

    @Test
    @DisplayName("爬取物品")
    void spiderItems_shouldFetchAndPersistItems() {
        tornBaseDataService.spiderItems();

        List<TornItemsDO> items = itemsDao.list();
        assertNotNull(items);
        assertFalse(items.isEmpty(), "物品列表不应为空");

        // 验证物品基本字段完整性
        for (TornItemsDO item : items) {
            assertNotNull(item.getId(), "物品ID不应为空");
        }

        // 验证缓存刷新被调用（itemsManager 是真实 bean，缓存应已刷新）
        // 验证趋势消息发送被 mock 拦截
        verify(itemTrendManager).sendTrendMsg();
    }

    @Test
    @DisplayName("重复抓取物品测试")
    void spiderItems_calledTwice_shouldHandleUpdateCorrectly() {
        // 第一次：全部走 insert
        tornBaseDataService.spiderItems();
        List<TornItemsDO> firstBatch = itemsDao.list();
        assertFalse(firstBatch.isEmpty());

        int countAfterFirstCall = firstBatch.size();

        // 第二次：应全部走 update，数量不变
        tornBaseDataService.spiderItems();
        List<TornItemsDO> secondBatch = itemsDao.list();
        assertEquals(countAfterFirstCall, secondBatch.size(), "第二次调用不应产生新增记录");
    }

    @Test
    @DisplayName("基础数据爬取集成测试")
    void spiderBaseData_shouldExecuteAllStepsAndScheduleNextTask() {
        tornBaseDataService.spiderBaseData();

        // 验证银行利率已写入
        String bankRate = settingDao.querySettingValue(SettingConstants.KEY_BANK_RATE);
        assertNotNull(bankRate);

        // 验证PT价值已写入
        String pointValue = settingDao.querySettingValue(SettingConstants.KEY_POINT_VALUE);
        assertNotNull(pointValue);

        // 验证物品已写入
        List<TornItemsDO> items = itemsDao.list();
        assertFalse(items.isEmpty());

        // 验证基础数据加载日期已更新
        String loadDate = settingDao.querySettingValue(SettingConstants.KEY_BASE_DATA_LOAD);
        assertNotNull(loadDate, "基础数据加载日期应已更新");

        // 验证下一次定时任务已注册
        verify(taskService).updateTask(eq("base-data-reload"), any(Runnable.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("Torn Api - 银行利率")
    void tornApi_bankEndpoint_shouldReturnValidResponse() {
        TornBankVO bank = tornApi.sendRequest(new TornBankDTO(), TornBankVO.class);
        assertNotNull(bank);
        assertNotNull(bank.getBank());
        TornBankRateVO rate = bank.getBank().stream()
                .filter(item -> Integer.valueOf(90).equals(item.getDays()))
                .findFirst()
                .orElseThrow();
        assertTrue(rate.getRate().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    @DisplayName("Torn Api - 银行利率新响应结构")
    void tornBankVO_shouldDeserializeCurrentArrayResponse() {
        String json = "{\"bank\":[{\"days\":7,\"rate\":38.17},{\"days\":14,\"rate\":43.08},"
                + "{\"days\":30,\"rate\":44.91},{\"days\":60,\"rate\":44.23},"
                + "{\"days\":90,\"rate\":49.2}]}";

        TornBankVO bank = JsonUtils.jsonToObj(json, TornBankVO.class);

        assertNotNull(bank.getBank());
        assertEquals(5, bank.getBank().size());
        assertEquals(new BigDecimal("49.2"), bank.getBank().get(4).getRate());
    }

    @Test
    @DisplayName("Torn Api - Point价格")
    void tornApi_statsEndpoint_shouldReturnValidResponse() {
        TornStatsVO stats = tornApi.sendRequest(new TornStatsDTO(), TornStatsVO.class);
        assertNotNull(stats);
        assertNotNull(stats.getStats());
        assertTrue(stats.getStats().containsKey("points_averagecost"), "应包含PT均价");
        assertTrue(stats.getStats().get("points_averagecost") > 0);
    }

    @Test
    @DisplayName("Torn Api - 物品")
    void tornApi_itemsEndpoint_shouldReturnValidResponse() {
        TornItemsListVO resp = tornApi.sendRequest(new TornItemsDTO(), TornItemsListVO.class);
        assertNotNull(resp);
        assertNotNull(resp.getItems());
        assertFalse(resp.getItems().isEmpty(), "物品列表不应为空");
    }
}
