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
    }

    /**
     * 声明更新链自返回桩；仅由确认会触发更新的用例调用，避免严格模式下无谓桩。
     */
    private void stubUpdateChain() {
        when(slotDao.lambdaUpdate()).thenReturn(updateWrapper);
        when(updateWrapper.set(any(), any())).thenReturn(updateWrapper);
        when(updateWrapper.eq(any(), any())).thenReturn(updateWrapper);
        when(updateWrapper.update()).thenReturn(true);
    }

    @Test
    @DisplayName("API返回不可用道具时写入道具ID和false")
    void unavailableItem_shouldWriteItemIdAndFalse() {
        stubUpdateChain();
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
        stubUpdateChain();
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
        stubUpdateChain();
        TornFactionCrimeSlotVO slot = new TornFactionCrimeSlotVO();
        slot.setUser(null);

        slotManager.updateSlotData(slot, BigDecimal.ZERO, oldSlot(1L));

        verifySet(TornFactionOcSlotDO::getRequiredItemId, null);
        verifySet(TornFactionOcSlotDO::getRequiredItemAvailable, null);
        verify(updateWrapper).update();
    }

    @Test
    @DisplayName("采集更新：用户与进度未变仅道具快照变化时仍更新既有槽位并写入新快照")
    void updateOcSlot_itemSnapshotChanged_shouldUpdateExistingSlot() {
        stubUpdateChain();
        TornFactionOcDO oldOc = new TornFactionOcDO();
        oldOc.setId(1L);
        TornFactionOcSlotDO oldSlot = oldSlot(9L);
        oldSlot.setOcId(1L);
        oldSlot.setPosition("Kidnap#1");
        oldSlot.setUserId(100L);
        oldSlot.setProgress(BigDecimal.valueOf(50));
        when(slotDao.queryListByOc(anyCollection())).thenReturn(List.of(oldSlot));

        TornFactionCrimeVO oc = crimeOcWithSlot(crimeSlot(100L, BigDecimal.valueOf(50), unavailableRequirement()));
        slotManager.updateOcSlot(List.of(oc), List.of(oldOc));

        verifySet(TornFactionOcSlotDO::getRequiredItemId, 100);
        verifySet(TornFactionOcSlotDO::getRequiredItemAvailable, false);
        verify(updateWrapper).update();
    }

    @Test
    @DisplayName("采集更新：用户进度与道具快照均未变化时不重复更新既有槽位")
    void updateOcSlot_unchanged_shouldSkipUpdate() {
        TornFactionOcDO oldOc = new TornFactionOcDO();
        oldOc.setId(1L);
        TornFactionOcSlotDO oldSlot = oldSlot(9L);
        oldSlot.setOcId(1L);
        oldSlot.setPosition("Kidnap#1");
        oldSlot.setUserId(100L);
        oldSlot.setProgress(BigDecimal.valueOf(50));
        oldSlot.setRequiredItemId(100);
        oldSlot.setRequiredItemAvailable(false);
        when(slotDao.queryListByOc(anyCollection())).thenReturn(List.of(oldSlot));

        TornFactionCrimeVO oc = crimeOcWithSlot(crimeSlot(100L, BigDecimal.valueOf(50), unavailableRequirement()));
        slotManager.updateOcSlot(List.of(oc), List.of(oldOc));

        verify(slotDao, never()).lambdaUpdate();
        verify(updateWrapper, never()).update();
    }

    @Test
    @DisplayName("首次转换：不可用道具映射道具ID与false")
    void convert2SlotDO_unavailableItem_shouldMapItemIdAndFalse() {
        TornFactionCrimeSlotVO slot = crimeSlot(100L, BigDecimal.valueOf(50), unavailableRequirement());

        TornFactionOcSlotDO slotDO = slot.convert2SlotDO(1L);

        assertEquals("Kidnap#1", slotDO.getPosition());
        assertEquals(Long.valueOf(100L), slotDO.getUserId());
        assertEquals(BigDecimal.valueOf(50), slotDO.getProgress());
        assertEquals(Integer.valueOf(100), slotDO.getRequiredItemId());
        assertEquals(Boolean.FALSE, slotDO.getRequiredItemAvailable());
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

    private TornFactionCrimeVO crimeOcWithSlot(TornFactionCrimeSlotVO slot) {
        TornFactionCrimeVO oc = new TornFactionCrimeVO();
        oc.setId(1L);
        oc.setSlots(List.of(slot));
        return oc;
    }

    private TornFactionCrimeSlotVO crimeSlot(Long userId, BigDecimal progress,
                                             TornFactionCrimeRequireItemVO requirement) {
        TornFactionCrimeSlotVO slot = slotWithUser(userId, progress);
        slot.setPosition("Kidnap");
        TornFactionCrimeSlotPositionVO positionInfo = new TornFactionCrimeSlotPositionVO();
        positionInfo.setNumber(1);
        slot.setPositionInfo(positionInfo);
        slot.setItemRequirement(requirement);
        return slot;
    }

    private TornFactionCrimeRequireItemVO unavailableRequirement() {
        TornFactionCrimeRequireItemVO requirement = new TornFactionCrimeRequireItemVO();
        requirement.setId(100);
        requirement.setIsAvailable(false);
        return requirement;
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
