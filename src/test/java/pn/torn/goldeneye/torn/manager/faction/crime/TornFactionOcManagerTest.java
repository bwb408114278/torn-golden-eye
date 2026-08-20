package pn.torn.goldeneye.torn.manager.faction.crime;

import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;
import pn.torn.goldeneye.torn.service.faction.oc.income.TornOcBatchIncomeService;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * OC数据管理器测试，验证活动OC刷新时只补齐缺失的Torn权威创建时间。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC数据管理器测试")
class TornFactionOcManagerTest {
    @Mock
    private ThreadPoolTaskExecutor virtualThreadExecutor;
    @Mock
    private TornOcBatchIncomeService ocBatchIncomeService;
    @Mock
    private TornFactionOcSlotManager slotManager;
    @Mock
    private TornFactionOcUserManager ocUserManager;
    @Mock
    private TornItemsManager itemsManager;
    @Mock
    private TornFactionOcDAO ocDao;
    @Mock
    private TornFactionOcSlotDAO slotDao;
    @Mock
    private LambdaUpdateChainWrapper<TornFactionOcDO> updateWrapper;

    private TornFactionOcManager ocManager;

    @BeforeEach
    void setUp() {
        ocManager = new TornFactionOcManager(virtualThreadExecutor, ocBatchIncomeService,
                slotManager, ocUserManager, itemsManager, ocDao, slotDao);
    }

    @Test
    @DisplayName("Recruiting活动OC在准备时间不变时补齐created_at")
    void recruitingOc_shouldBackfillMissingTornCreatedAt() {
        LocalDateTime readyTime = LocalDateTime.of(2026, 8, 20, 12, 0);
        TornFactionOcDO oldOc = oldOc("Recruiting", readyTime, null);
        TornFactionCrimeVO apiOc = apiOc(1L, readyTime, 1787211787L);
        when(ocDao.queryListByIdList(2095L, List.of(1L))).thenReturn(List.of(oldOc));
        when(ocDao.lambdaUpdate()).thenReturn(updateWrapper);
        when(updateWrapper.set(any(), any())).thenReturn(updateWrapper);
        when(updateWrapper.set(anyBoolean(), any(), any())).thenReturn(updateWrapper);
        when(updateWrapper.eq(any(), any())).thenReturn(updateWrapper);
        when(updateWrapper.update()).thenReturn(true);

        ocManager.updateAvailableOcData(2095L, List.of(apiOc));

        verify(ocDao).lambdaUpdate();
        verify(updateWrapper).update();
        verify(slotManager).updateOcSlot(List.of(apiOc), List.of(oldOc));
    }

    @Test
    @DisplayName("Planning活动OC已有创建时间时不因API缺失而清空")
    void planningOc_shouldKeepExistingTornCreatedAtWhenApiOmitsValue() {
        LocalDateTime readyTime = LocalDateTime.of(2026, 8, 20, 12, 0);
        LocalDateTime existingCreatedAt = LocalDateTime.of(2026, 8, 19, 10, 0);
        TornFactionOcDO oldOc = oldOc("Planning", readyTime, existingCreatedAt);
        TornFactionCrimeVO apiOc = apiOc(1L, readyTime, null);
        when(ocDao.queryListByIdList(2095L, List.of(1L))).thenReturn(List.of(oldOc));

        ocManager.updateAvailableOcData(2095L, List.of(apiOc));

        verify(ocDao, never()).lambdaUpdate();
        verify(slotManager).updateOcSlot(List.of(apiOc), List.of(oldOc));
    }

    private TornFactionOcDO oldOc(String status, LocalDateTime readyTime, LocalDateTime createdAt) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(1L);
        oc.setFactionId(2095L);
        oc.setStatus(status);
        oc.setReadyTime(readyTime);
        oc.setTornCreatedAt(createdAt);
        return oc;
    }

    private TornFactionCrimeVO apiOc(long id, LocalDateTime readyTime, Long createdAt) {
        TornFactionCrimeVO oc = new TornFactionCrimeVO();
        oc.setId(id);
        oc.setReadyAt(readyTime.toEpochSecond(java.time.ZoneOffset.ofHours(8)));
        oc.setCreatedAt(createdAt);
        oc.setStatus("Recruiting");
        oc.setSlots(List.of());
        return oc;
    }
}
