package pn.torn.goldeneye.torn.service.faction.oc;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.lark.oapi.core.response.BaseResponse;
import com.lark.oapi.service.bitable.v1.model.AppTableRecord;
import com.lark.oapi.service.bitable.v1.model.SearchAppTableRecordRespBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.base.larksuite.LarkSuiteManualReqParam;
import pn.torn.goldeneye.base.larksuite.LarkSuiteReqParam;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteBitTableProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Torn OC收益采集服务测试
 *
 * @author Bai
 * @version 1.2.2
 * @since 2026.06.16
 */
@ExtendWith(MockitoExtension.class)
class TornFactionOcBenefitServiceTest {
    @Mock
    private DynamicTaskService taskService;
    @Mock
    private ThreadPoolTaskExecutor virtualThreadExecutor;
    @Mock
    private TornFactionOcBenefitDAO benefitDao;
    @Mock
    private SysSettingDAO settingDao;
    @Mock
    private ProjectProperty projectProperty;
    @Mock
    private TornSettingFactionManager factionManager;

    private TornFactionOcBenefitService benefitService;

    @BeforeEach
    void setUp() {
        LarkSuiteProperty property = new LarkSuiteProperty();
        LarkSuiteBitTableProperty bitTable = new LarkSuiteBitTableProperty();
        bitTable.setName(TornConstants.BIT_TABLE_OC_BENEFIT);
        bitTable.setAppToken("app-token");
        bitTable.setTableId("table-id");
        bitTable.setViewId("view-id");
        property.setBitTable(List.of(bitTable));

        TornSettingFactionDO faction = new TornSettingFactionDO();
        faction.setId(1000L);
        faction.setFactionAlias("PN");
        when(factionManager.getAliasMap()).thenReturn(Map.of("PN", faction));

        benefitService = new TornFactionOcBenefitService(taskService, virtualThreadExecutor,
                buildChainLarkSuiteApi(), factionManager, benefitDao, settingDao, projectProperty, property);
        ReflectionTestUtils.setField(benefitService, "factionMap", factionManager.getAliasMap());
    }

    @Test
    @DisplayName("三段链式OC：最终OC有两个前置时应回溯完整链并保存所有参与人收益")
    void testSpiderOcBenefit_ThreeStepChain() {
        LambdaQueryChainWrapper<TornFactionOcBenefitDO> query = mock();
        when(benefitDao.lambdaQuery()).thenReturn(query);
        when(query.in(any(), anyCollection())).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        benefitService.spiderOcBenefit(LocalDateTime.of(2026, 6, 13, 0, 0),
                LocalDateTime.of(2026, 6, 14, 0, 0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TornFactionOcBenefitDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(benefitDao).saveBatch(captor.capture());

        List<TornFactionOcBenefitDO> saved = captor.getValue();
        assertEquals(3, saved.size());
        assertEquals(List.of(101L, 102L, 103L), saved.stream().map(TornFactionOcBenefitDO::getOcId).toList());
        assertEquals(List.of(3001L, 3002L, 3003L), saved.stream().map(TornFactionOcBenefitDO::getUserId).toList());
        assertEquals(List.of(100_000L, 200_000L, 300_000L), saved.stream().map(TornFactionOcBenefitDO::getBenefitMoney).toList());
        verify(settingDao).updateSetting(eq("OC_BENEFIT_LOAD_TIME"), eq("2026-06-14 00:00:00"));
        verify(taskService).updateTask(eq("oc-benefit-reload"), any(Runnable.class), eq(LocalDateTime.of(2026, 6, 14, 1, 0)));
    }

    @Test
    @DisplayName("同一批次内重复的 ocId+userId 只保存一次")
    void testSaveBenefitList_DeduplicateByOcAndUser() {
        LambdaQueryChainWrapper<TornFactionOcBenefitDO> query = mock();
        when(benefitDao.lambdaQuery()).thenReturn(query);
        when(query.in(any(), anyCollection())).thenReturn(query);
        when(query.list()).thenReturn(List.of());

        TornFactionOcBenefitDO first = buildBenefit(1707961L, 2076124L, 100L);
        TornFactionOcBenefitDO second = buildBenefit(1707961L, 2076124L, 200L);

        benefitService.saveBenefitList(List.of(first, second));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TornFactionOcBenefitDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(benefitDao).saveBatch(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(100L, captor.getValue().getFirst().getBenefitMoney());
    }

    /**
     * 构建收益数据
     */
    private TornFactionOcBenefitDO buildBenefit(Long ocId, Long userId, Long benefitMoney) {
        TornFactionOcBenefitDO benefit = new TornFactionOcBenefitDO();
        benefit.setOcId(ocId);
        benefit.setUserId(userId);
        benefit.setBenefitMoney(benefitMoney);
        return benefit;
    }

    /**
     * 构建飞书链路API
     */
    private LarkSuiteApi buildChainLarkSuiteApi() {
        AppTableRecord step1 = data(101L, "前置1", 8, null, 102L,
                "3001", "100000", 1, 3001L, "Muscle", 0.6, 10_000L,
                LocalDateTime.of(2026, 5, 31, 23, 0));
        AppTableRecord step2 = data(102L, "前置2", 9, 101L, 103L,
                "3002", "200000", 1, 3002L, "Driver", 0.7, 20_000L,
                LocalDateTime.of(2026, 6, 10, 23, 0));
        AppTableRecord step3 = data(103L, "最终10级", 10, 102L, null,
                "3001,3002,3003", "100000,200000,300000", 1, 3003L, "Hacker", 0.8, 30_000L,
                LocalDateTime.of(2026, 6, 13, 23, 0));

        SearchAppTableRecordRespBody firstPage = new SearchAppTableRecordRespBody();
        firstPage.setItems(new AppTableRecord[]{step3});
        firstPage.setHasMore(false);

        SearchAppTableRecordRespBody findStep2 = new SearchAppTableRecordRespBody();
        findStep2.setItems(new AppTableRecord[]{step2});
        findStep2.setHasMore(false);

        SearchAppTableRecordRespBody findStep1 = new SearchAppTableRecordRespBody();
        findStep1.setItems(new AppTableRecord[]{step1});
        findStep1.setHasMore(false);

        Queue<SearchAppTableRecordRespBody> responses = new ArrayDeque<>(List.of(firstPage, findStep2, findStep1));
        return new LarkSuiteApi() {
            @Override
            public <D, T extends BaseResponse<D>> D sendRequest(LarkSuiteManualReqParam param, String tenantToken, Class<T> responseType) {
                return null;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <D, T extends BaseResponse<D>> D sendRequest(LarkSuiteReqParam<D, T> param) {
                return (D) responses.remove();
            }

            @Override
            public <D, T extends BaseResponse<D>> D sendSelfRequest(LarkSuiteReqParam<D, T> param) {
                return null;
            }
        };
    }

    /**
     * 构建飞书数据
     */
    private AppTableRecord data(Long ocId, String name, Integer rank, Long previous, Long next,
                                String userIds, String bonus, int slot, Long slotUserId,
                                String position, double chance, Long itemCost,
                                LocalDateTime finishTime) {
        AppTableRecord data = new AppTableRecord();
        Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("帮派", "PN");
        fields.put("当前状态", "Successful");
        fields.put("OCID", ocId);
        fields.put("OC名称", name);
        fields.put("难度等级", rank);
        fields.put("参与人id字符串", userIds);
        fields.put("个人奖金", bonus);
        fields.put("实际完成时间", finishTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        fields.put("slot_" + slot + "_user_id", slotUserId);
        fields.put("slot_" + slot + "_position", position);
        fields.put("slot_" + slot + "_chance", chance);
        fields.put("slot_" + slot + "_item_marketvalue", itemCost);
        if (previous != null) {
            fields.put("previous", previous);
        }
        if (next != null) {
            fields.put("next", next);
        }
        data.setFields(fields);
        return data;
    }
}
