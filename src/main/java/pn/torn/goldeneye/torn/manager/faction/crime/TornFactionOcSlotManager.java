package pn.torn.goldeneye.torn.manager.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeSlotVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * OC岗位公共逻辑层
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.08.31
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcSlotManager {
    private final TornFactionOcSlotDAO slotDao;

    /**
     * 更新OC岗位
     */
    public void updateOcSlot(List<TornFactionCrimeVO> ocList, List<TornFactionOcDO> oldDataList) {
        List<TornFactionOcSlotDO> oldSlotList = slotDao.queryListByOc(oldDataList);
        for (TornFactionCrimeVO oc : ocList) {
            for (TornFactionCrimeSlotVO slot : oc.getSlots()) {
                TornFactionOcSlotDO oldSlot = findOldSlot(oldSlotList, oc, slot);
                if (oldSlot != null && shouldUpdateSlot(oldSlot, slot)) {
                    updateSlotData(slot, resolveProgress(slot), oldSlot);
                }
            }
        }
    }

    private TornFactionOcSlotDO findOldSlot(List<TornFactionOcSlotDO> oldSlotList,
                                            TornFactionCrimeVO oc,
                                            TornFactionCrimeSlotVO slot) {
        String position = slot.getPosition() + "#" + slot.getPositionInfo().getNumber();
        return oldSlotList.stream()
                .filter(old -> old.getOcId().equals(oc.getId()) && old.getPosition().equals(position))
                .findAny().orElse(null);
    }

    private boolean shouldUpdateSlot(TornFactionOcSlotDO oldSlot, TornFactionCrimeSlotVO slot) {
        boolean itemSnapshotChanged = !Objects.equals(oldSlot.getRequiredItemId(), resolveRequiredItemId(slot))
                || !Objects.equals(oldSlot.getRequiredItemAvailable(), resolveRequiredItemAvailable(slot).orElse(null));
        boolean userAndProgressChanged = !Objects.equals(oldSlot.getUserId(), slot.getUserId())
                || !Objects.equals(oldSlot.getProgress(), resolveProgress(slot));
        return userAndProgressChanged || itemSnapshotChanged;
    }

    private BigDecimal resolveProgress(TornFactionCrimeSlotVO slot) {
        return slot.getUser() == null ? BigDecimal.ZERO : slot.getUser().getProgress();
    }

    /**
     * 更新Slot数据
     */
    public void updateSlotData(TornFactionCrimeSlotVO slot, BigDecimal progress, TornFactionOcSlotDO oldSlot) {
        if (slot.getUser() != null) {
            slotDao.lambdaUpdate()
                    .set(TornFactionOcSlotDO::getUserId, slot.getUser().getId())
                    .set(TornFactionOcSlotDO::getJoinTime, DateTimeUtils.convertToDateTime(slot.getUser().getJoinedAt()))
                    .set(TornFactionOcSlotDO::getPassRate, slot.getCheckpointPassRate())
                    .set(TornFactionOcSlotDO::getProgress, progress)
                    .set(TornFactionOcSlotDO::getRequiredItemId, resolveRequiredItemId(slot))
                    .set(TornFactionOcSlotDO::getRequiredItemAvailable, resolveRequiredItemAvailable(slot).orElse(null))
                    .eq(TornFactionOcSlotDO::getId, oldSlot.getId())
                    .update();
        } else {
            slotDao.lambdaUpdate()
                    .set(TornFactionOcSlotDO::getUserId, null)
                    .set(TornFactionOcSlotDO::getJoinTime, null)
                    .set(TornFactionOcSlotDO::getPassRate, null)
                    .set(TornFactionOcSlotDO::getProgress, BigDecimal.ZERO)
                    .set(TornFactionOcSlotDO::getRequiredItemId, null)
                    .set(TornFactionOcSlotDO::getRequiredItemAvailable, null)
                    .eq(TornFactionOcSlotDO::getId, oldSlot.getId())
                    .update();
        }
    }

    private Integer resolveRequiredItemId(TornFactionCrimeSlotVO slot) {
        if (slot.getUser() == null || slot.getItemRequirement() == null) {
            return null;
        }
        return slot.getItemRequirement().getId();
    }

    private Optional<Boolean> resolveRequiredItemAvailable(TornFactionCrimeSlotVO slot) {
        if (slot.getUser() == null || slot.getItemRequirement() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(slot.getItemRequirement().getIsAvailable());
    }
}