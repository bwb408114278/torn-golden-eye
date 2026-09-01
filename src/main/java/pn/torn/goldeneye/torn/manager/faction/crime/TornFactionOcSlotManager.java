package pn.torn.goldeneye.torn.manager.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeSlotVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OC岗位公共逻辑层
 *
 * @author Bai
 * @version 1.6.0
 * @since 2025.08.31
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcSlotManager {
    private final TornFactionOcSlotDAO slotDao;

    /**
     * 更新OC岗位及其需求快照。
     *
     * @param ocList Torn API返回的OC列表
     * @param oldDataList 本地已有的OC列表
     */
    public void updateOcSlot(List<TornFactionCrimeVO> ocList, List<TornFactionOcDO> oldDataList) {
        if (CollectionUtils.isEmpty(ocList)) {
            return;
        }

        List<TornFactionOcSlotDO> oldSlotList = slotDao.queryListByOc(oldDataList);
        Map<SlotKey, TornFactionOcSlotDO> oldSlotMap = indexOldSlots(oldSlotList);
        for (TornFactionCrimeVO oc : ocList) {
            if (oc == null || CollectionUtils.isEmpty(oc.getSlots())) {
                continue;
            }
            for (TornFactionCrimeSlotVO slot : oc.getSlots()) {
                String position = resolvePosition(slot);
                if (position == null) {
                    continue;
                }

                TornFactionOcSlotDO oldSlot = oldSlotMap.get(new SlotKey(oc.getId(), position));
                if (oldSlot == null || !shouldUpdateSlot(oldSlot, slot)) {
                    continue;
                }

                updateSlotData(slot, resolveProgress(slot), oldSlot);
            }
        }
    }

    private Map<SlotKey, TornFactionOcSlotDO> indexOldSlots(List<TornFactionOcSlotDO> oldSlotList) {
        Map<SlotKey, TornFactionOcSlotDO> oldSlotMap = new HashMap<>();
        for (TornFactionOcSlotDO oldSlot : oldSlotList) {
            oldSlotMap.putIfAbsent(new SlotKey(oldSlot.getOcId(), oldSlot.getPosition()), oldSlot);
        }
        return oldSlotMap;
    }

    private boolean shouldUpdateSlot(TornFactionOcSlotDO oldSlot, TornFactionCrimeSlotVO slot) {
        RequiredItemSnapshot snapshot = resolveRequiredItemSnapshot(slot);
        return !Objects.equals(oldSlot.getUserId(), slot.getUserId())
                || !Objects.equals(oldSlot.getProgress(), resolveProgress(slot))
                || !Objects.equals(oldSlot.getRequiredItemId(), snapshot.itemId())
                || !Objects.equals(oldSlot.getRequiredItemAvailable(), snapshot.available());
    }

    private BigDecimal resolveProgress(TornFactionCrimeSlotVO slot) {
        return slot.getUser() == null ? BigDecimal.ZERO : slot.getUser().getProgress();
    }

    private RequiredItemSnapshot resolveRequiredItemSnapshot(TornFactionCrimeSlotVO slot) {
        if (slot.getUser() == null || slot.getItemRequirement() == null
                || slot.getItemRequirement().getId() == null) {
            return new RequiredItemSnapshot(null, null);
        }
        return new RequiredItemSnapshot(slot.getItemRequirement().getId(),
                slot.getItemRequirement().getIsAvailable());
    }

    private String resolvePosition(TornFactionCrimeSlotVO slot) {
        if (slot == null || slot.getPosition() == null || slot.getPositionInfo() == null
                || slot.getPositionInfo().getNumber() == null) {
            return null;
        }
        return slot.getPosition() + "#" + slot.getPositionInfo().getNumber();
    }

    /**
     * 更新指定本地槽位的数据和需求快照。
     *
     * @param slot Torn API返回的槽位
     * @param progress 本次同步的准备进度
     * @param oldSlot 本地已有的槽位
     */
    public void updateSlotData(TornFactionCrimeSlotVO slot, BigDecimal progress, TornFactionOcSlotDO oldSlot) {
        RequiredItemSnapshot snapshot = resolveRequiredItemSnapshot(slot);
        if (slot.getUser() != null) {
            slotDao.lambdaUpdate()
                    .set(TornFactionOcSlotDO::getUserId, slot.getUser().getId())
                    .set(TornFactionOcSlotDO::getJoinTime, DateTimeUtils.convertToDateTime(slot.getUser().getJoinedAt()))
                    .set(TornFactionOcSlotDO::getPassRate, slot.getCheckpointPassRate())
                    .set(TornFactionOcSlotDO::getProgress, progress)
                    .set(TornFactionOcSlotDO::getRequiredItemId, snapshot.itemId())
                    .set(TornFactionOcSlotDO::getRequiredItemAvailable, snapshot.available())
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

    /**
     * 按OC和岗位位置唯一定位一个本地槽位，避免使用有歧义的拼接字符串索引。
     *
     * @param ocId OC ID
     * @param position 岗位位置
     */
    private record SlotKey(Long ocId, String position) {
    }

    /**
     * API返回的道具需求快照。
     *
     * @param itemId 道具ID
     * @param available 道具是否可用
     */
    private record RequiredItemSnapshot(Integer itemId, Boolean available) {
    }
}
