package pn.torn.goldeneye.torn.manager.faction.crime;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeRequireItemVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeSlotVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeUserVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OC岗位快照同步测试。
 * <p>
 * 验证道具需求写库、可确认后清空旧快照、空槽清空快照。
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC岗位快照同步测试")
class TornFactionOcSlotManagerTest {

    @Mock
    private TornFactionOcSlotDAO slotDao;
    @Mock
    private LambdaUpdateChainWrapper<TornFactionOcSlotDO> updateWrapper;

    private TornFactionOcSlotManager slotManager;

    @BeforeEach
    void setUp() {
        slotManager = new TornFactionOcSlotManager(slotDao);
        when(slotDao.lambdaUpdate()).thenReturn(updateWrapper);
        when(updateWrapper.set(any(), any())).thenReturn(updateWrapper);
        when(updateWrapper.eq(any(), any())).thenReturn(updateWrapper);
        when(updateWrapper.update()).thenReturn(true);
    }

    @Test
    @DisplayName("API返回不可用道具时写入道具ID和false")
    void unavailableItem_shouldWriteItemIdAndFalse() {
        TornFactionCrimeSlotVO slot = slotWithUser(1L, BigDecimal.valueOf(50));
        TornFactionCrimeRequireItemVO requirement = new TornFactionCrimeRequireItemVO();
        requirement.setId(100);
        requirement.setIsAvailable(false);
        slot.setItemRequirement(requirement);

        slotManager.updateSlotData(slot, BigDecimal.valueOf(50), oldSlot(1L));

        verifySet(TornFactionOcSlotDO::getRequiredItemId, 100);
        verifySet(TornFactionOcSlotDO::getRequiredItemAvailable, false);
        verify(updateWrapper).update();
    }

    @Test
    @DisplayName("后续API无道具需求时清空旧快照")
    void noRequirement_shouldClearItemSnapshot() {
        TornFactionCrimeSlotVO slot = slotWithUser(1L, BigDecimal.valueOf(50));
        slot.setItemRequirement(null);

        slotManager.updateSlotData(slot, BigDecimal.valueOf(50), oldSlot(1L));

        verifySet(TornFactionOcSlotDO::getRequiredItemId, null);
        verifySet(TornFactionOcSlotDO::getRequiredItemAvailable, null);
        verify(updateWrapper).update();
    }

    @Test
    @DisplayName("槽位无人时清空道具快照")
    void emptyUser_shouldClearItemSnapshot() {
        TornFactionCrimeSlotVO slot = new TornFactionCrimeSlotVO();
        slot.setUser(null);

        slotManager.updateSlotData(slot, BigDecimal.ZERO, oldSlot(1L));

        verifySet(TornFactionOcSlotDO::getRequiredItemId, null);
        verifySet(TornFactionOcSlotDO::getRequiredItemAvailable, null);
        verify(updateWrapper).update();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void verifySet(SFunction<TornFactionOcSlotDO, ?> expectedColumn, Object expectedValue) {
        ArgumentCaptor<SFunction> columnCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(SFunction.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(updateWrapper, atLeastOnce()).set(columnCaptor.capture(), valueCaptor.capture());

        TornFactionOcSlotDO sample = new TornFactionOcSlotDO();
        sample.setUserId(-888L);
        sample.setJoinTime(LocalDateTime.of(2000, 1, 1, 0, 0));
        sample.setPassRate(-999);
        sample.setProgress(BigDecimal.TEN);
        sample.setRequiredItemId(Integer.MIN_VALUE);
        sample.setRequiredItemAvailable(Boolean.TRUE);

        Object expectedColumnValue = expectedColumn.apply(sample);
        for (int i = 0; i < columnCaptor.getAllValues().size(); i++) {
            Object actualColumnValue = columnCaptor.getAllValues().get(i).apply(sample);
            if (Objects.equals(actualColumnValue, expectedColumnValue)) {
                assertEquals(expectedValue, valueCaptor.getAllValues().get(i));
                return;
            }
        }
        fail("未找到目标update列: " + expectedColumn);
    }

    private TornFactionCrimeSlotVO slotWithUser(Long userId, BigDecimal progress) {
        TornFactionCrimeUserVO user = new TornFactionCrimeUserVO();
        user.setId(userId);
        user.setJoinedAt(1787211787L);
        user.setProgress(progress);
        TornFactionCrimeSlotVO slot = new TornFactionCrimeSlotVO();
        slot.setUser(user);
        return slot;
    }

    private TornFactionOcSlotDO oldSlot(Long id) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setId(id);
        return slot;
    }
}
