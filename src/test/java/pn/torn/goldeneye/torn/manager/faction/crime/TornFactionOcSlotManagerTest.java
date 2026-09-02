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
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * OC岗位快照同步测试。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
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
    }

    @Test
    @DisplayName("首次创建写入不可用道具快照")
    void convert2SlotDO_shouldWriteRequiredItemSnapshot() {
        TornFactionCrimeSlotVO slot = apiSlot(BigDecimal.valueOf(50));
        slot.setItemRequirement(requirement(100, false));

        TornFactionOcSlotDO result = slot.convert2SlotDO(1L);

        assertEquals(Integer.valueOf(100), result.getRequiredItemId());
        assertEquals(Boolean.FALSE, result.getRequiredItemAvailable());
    }

    @Test
    @DisplayName("道具恢复可用时更新快照，即使用户和进度未变化")
    void updateOcSlot_shouldUpdateRecoveredItemSnapshot() {
        stubUpdateChain();
        TornFactionOcSlotDO oldSlot = oldSlot(false);
        when(slotDao.queryListByOc(anyCollection())).thenReturn(List.of(oldSlot));

        TornFactionCrimeSlotVO slot = apiSlot(BigDecimal.valueOf(50));
        slot.setItemRequirement(requirement(100, true));
        slotManager.updateOcSlot(List.of(apiOc(slot)), List.of(oldOc()));

        verifySet(TornFactionOcSlotDO::getRequiredItemAvailable, true);
        verify(updateWrapper).update();
    }

    @Test
    @DisplayName("无需求或无人时清理旧道具快照")
    void updateOcSlot_shouldClearSnapshotWhenRequirementUnknownOrUserEmpty() {
        stubUpdateChain();
        TornFactionOcSlotDO oldSlot = oldSlot(false);
        when(slotDao.queryListByOc(anyCollection())).thenReturn(List.of(oldSlot));

        TornFactionCrimeSlotVO slot = apiSlot(BigDecimal.valueOf(50));
        slot.setItemRequirement(null);
        slotManager.updateOcSlot(List.of(apiOc(slot)), List.of(oldOc()));
        verifySet(TornFactionOcSlotDO::getRequiredItemId, null);
        verifySet(TornFactionOcSlotDO::getRequiredItemAvailable, null);

        clearInvocations(updateWrapper);
        slot.setUser(null);
        slotManager.updateOcSlot(List.of(apiOc(slot)), List.of(oldOc()));
        verifySet(TornFactionOcSlotDO::getRequiredItemId, null);
        verifySet(TornFactionOcSlotDO::getRequiredItemAvailable, null);
    }

    @Test
    @DisplayName("需求可用性未知时写入null而不是false")
    void updateOcSlot_shouldNotPersistFalseForUnknownAvailability() {
        stubUpdateChain();
        TornFactionOcSlotDO oldSlot = oldSlot(false);
        when(slotDao.queryListByOc(anyCollection())).thenReturn(List.of(oldSlot));

        TornFactionCrimeSlotVO slot = apiSlot(BigDecimal.valueOf(50));
        TornFactionCrimeRequireItemVO requirement = requirement(100, false);
        requirement.setIsAvailable(null);
        slot.setItemRequirement(requirement);
        slotManager.updateOcSlot(List.of(apiOc(slot)), List.of(oldOc()));

        verifySet(TornFactionOcSlotDO::getRequiredItemAvailable, null);
        verify(updateWrapper).update();
    }

    @Test
    @DisplayName("API需求未知时不写入false")
    void convert2SlotDO_shouldKeepAvailabilityNullWhenItemIdUnknown() {
        TornFactionCrimeSlotVO slot = apiSlot(BigDecimal.valueOf(50));
        slot.setItemRequirement(requirement(null, false));

        TornFactionOcSlotDO result = slot.convert2SlotDO(1L);

        assertNull(result.getRequiredItemId());
        assertNull(result.getRequiredItemAvailable());
    }

    private void stubUpdateChain() {
        when(slotDao.lambdaUpdate()).thenReturn(updateWrapper);
        when(updateWrapper.set(any(), any())).thenReturn(updateWrapper);
        when(updateWrapper.eq(any(), any())).thenReturn(updateWrapper);
        when(updateWrapper.update()).thenReturn(true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void verifySet(SFunction<TornFactionOcSlotDO, ?> expectedColumn, Object expectedValue) {
        ArgumentCaptor<SFunction> columnCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(SFunction.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(updateWrapper, atLeastOnce()).set(columnCaptor.capture(), valueCaptor.capture());

        TornFactionOcSlotDO sample = new TornFactionOcSlotDO();
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

    private TornFactionCrimeVO apiOc(TornFactionCrimeSlotVO slot) {
        TornFactionCrimeVO oc = new TornFactionCrimeVO();
        oc.setId(1L);
        oc.setSlots(List.of(slot));
        return oc;
    }

    private TornFactionCrimeSlotVO apiSlot(BigDecimal progress) {
        TornFactionCrimeUserVO user = new TornFactionCrimeUserVO();
        user.setId(1L);
        user.setJoinedAt(1787211787L);
        user.setProgress(progress);
        TornFactionCrimeSlotVO slot = new TornFactionCrimeSlotVO();
        slot.setPosition("Kidnap");
        TornFactionCrimeSlotPositionVO positionInfo = new TornFactionCrimeSlotPositionVO();
        positionInfo.setNumber(1);
        slot.setPositionInfo(positionInfo);
        slot.setUser(user);
        return slot;
    }

    private TornFactionCrimeRequireItemVO requirement(Integer itemId, boolean available) {
        TornFactionCrimeRequireItemVO requirement = new TornFactionCrimeRequireItemVO();
        requirement.setId(itemId);
        requirement.setIsAvailable(available);
        return requirement;
    }

    private TornFactionOcDO oldOc() {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(1L);
        return oc;
    }

    private TornFactionOcSlotDO oldSlot(boolean available) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setId(9L);
        slot.setOcId(1L);
        slot.setPosition("Kidnap#1");
        slot.setUserId(1L);
        slot.setProgress(BigDecimal.valueOf(50));
        slot.setRequiredItemId(100);
        slot.setRequiredItemAvailable(available);
        slot.setJoinTime(LocalDateTime.of(2026, 8, 31, 12, 0));
        return slot;
    }
}
