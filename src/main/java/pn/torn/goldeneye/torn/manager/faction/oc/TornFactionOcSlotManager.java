package pn.torn.goldeneye.torn.manager.faction.oc;

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

/**
 * OC岗位公共逻辑层
 *
 * @author Bai
 * @version 0.2.0
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
                String position = slot.getPosition() + "#" + slot.getPositionNumber();
                TornFactionOcSlotDO oldSlot = oldSlotList.stream()
                        .filter(old -> old.getOcId().equals(oc.getId()) && old.getPosition().equals(position))
                        .findAny().orElse(null);
                BigDecimal progress = slot.getUser() == null ? BigDecimal.ZERO : slot.getUser().getProgress();
                if (oldSlot == null ||
                        (Objects.equals(oldSlot.getUserId(), slot.getUserId()) && oldSlot.getProgress().equals(progress))) {
                    continue;
                }

                if (slot.getUser() != null) {
                    slotDao.lambdaUpdate()
                            .set(TornFactionOcSlotDO::getUserId, slot.getUser().getId())
                            .set(TornFactionOcSlotDO::getJoinTime, DateTimeUtils.convertToDateTime(slot.getUser().getJoinedAt()))
                            .set(TornFactionOcSlotDO::getPassRate, slot.getCheckpointPassRate())
                            .set(TornFactionOcSlotDO::getProgress, progress)
                            .eq(TornFactionOcSlotDO::getId, oldSlot.getId())
                            .update();
                } else {
                    slotDao.lambdaUpdate()
                            .set(TornFactionOcSlotDO::getUserId, null)
                            .set(TornFactionOcSlotDO::getJoinTime, null)
                            .set(TornFactionOcSlotDO::getPassRate, null)
                            .set(TornFactionOcSlotDO::getProgress, BigDecimal.ZERO)
                            .eq(TornFactionOcSlotDO::getId, oldSlot.getId())
                            .update();
                }
            }
        }
    }
}