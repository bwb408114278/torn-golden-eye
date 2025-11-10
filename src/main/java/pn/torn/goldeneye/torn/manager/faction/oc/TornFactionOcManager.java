package pn.torn.goldeneye.torn.manager.faction.oc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeSlotVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OC公共逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.08
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcManager {
    private final TornFactionOcSlotManager slotManager;
    private final TornFactionOcUserManager ocUserManager;
    private final TornItemsManager itemsManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;

    /**
     * 更新OC数据
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateOc(long factionId, List<TornFactionCrimeVO> availableList, List<TornFactionCrimeVO> completeList) {
        List<Long> validOcIdList = updateAvailableOc(factionId, availableList);
        validOcIdList.addAll(completeOcData(factionId, completeList));
        deleteOcData(factionId, validOcIdList);
    }

    /**
     * 更新可用OC数据
     */
    public List<Long> updateAvailableOc(long factionId, List<TornFactionCrimeVO> ocList) {
        if (CollectionUtils.isEmpty(ocList)) {
            return new ArrayList<>();
        }

        List<TornFactionCrimeVO> newDataList = new ArrayList<>();
        List<TornFactionCrimeVO> updateDataList = new ArrayList<>();
        List<Long> ocIdList = ocList.stream().map(TornFactionCrimeVO::getId).toList();
        List<Long> validOcIdList = new ArrayList<>();
        List<TornFactionOcDO> oldDataList = ocDao.queryListByIdList(factionId, ocIdList);
        for (TornFactionCrimeVO oc : ocList) {
            validOcIdList.add(oc.getId());
            if (oldDataList.stream().anyMatch(r -> r.getId().equals(oc.getId()))) {
                updateDataList.add(oc);
            } else {
                newDataList.add(oc);
            }
        }

        insertOcData(factionId, newDataList);
        updateAvailableOcData(factionId, updateDataList);
        ocUserManager.updateJoinedUserPassRate(factionId, ocList);
        return validOcIdList;
    }

    /**
     * 更新完成OC数据
     */
    public List<Long> completeOcData(long factionId, List<TornFactionCrimeVO> ocList) {
        if (CollectionUtils.isEmpty(ocList)) {
            return List.of();
        }

        List<Long> ocIdList = ocList.stream().map(TornFactionCrimeVO::getId).toList();
        List<Long> validOcIdList = new ArrayList<>();
        List<TornFactionOcDO> planOcList = ocDao.lambdaQuery()
                .in(TornFactionOcDO::getId, ocIdList)
                .eq(TornFactionOcDO::getFactionId, factionId)
                .list();
        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(planOcList);
        List<TornFactionCrimeVO> lostData = new ArrayList<>();
        for (TornFactionCrimeVO oc : ocList) {
            TornFactionOcDO planOc = planOcList.stream()
                    .filter(o -> o.getId().equals(oc.getId()))
                    .findAny().orElse(null);
            if (planOc == null) {
                lostData.add(oc);
            } else if (TornOcStatusEnum.getCompleteStatusList().contains(planOc.getStatus())) {
                continue;
            }

            updateCompleteData(oc, slotList);
            validOcIdList.add(oc.getId());
        }

        insertOcData(factionId, lostData);
        return validOcIdList;
    }

    /**
     * 删除过期的OC
     *
     * @param factionId     帮派ID
     * @param validOcIdList 有人的OC ID列表
     */
    public void deleteOcData(long factionId, List<Long> validOcIdList) {
        if (!CollectionUtils.isEmpty(validOcIdList)) {
            List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                    .eq(TornFactionOcDO::getFactionId, factionId)
                    .notIn(TornFactionOcDO::getId, validOcIdList)
                    .notIn(TornFactionOcDO::getStatus, TornOcStatusEnum.getCompleteStatusList())
                    .list();
            if (CollectionUtils.isEmpty(ocList)) {
                return;
            }

            List<Long> ocIdList = ocList.stream().map(TornFactionOcDO::getId).toList();
            ocDao.deleteByIdList(ocIdList);
            slotDao.remove(new LambdaQueryWrapper<TornFactionOcSlotDO>()
                    .in(TornFactionOcSlotDO::getOcId, ocIdList));
        }
    }

    /**
     * 插入新OC
     */
    public void insertOcData(long factionId, List<TornFactionCrimeVO> ocList) {
        if (ocList.isEmpty()) {
            return;
        }

        Map<Integer, TornItemsDO> itemMap = itemsManager.getMap();
        List<TornFactionOcDO> dataList = ocList.stream()
                .map(oc -> oc.convert2DO(factionId, itemMap))
                .toList();
        ocDao.saveBatch(dataList);

        List<TornFactionOcSlotDO> slotList = new ArrayList<>();
        for (TornFactionCrimeVO oc : ocList) {
            slotList.addAll(oc.getSlots().stream().map(s -> s.convert2SlotDO(oc.getId())).toList());
        }
        slotDao.saveBatch(slotList);
    }

    /**
     * 更新可用OC
     */
    public void updateAvailableOcData(long factionId, List<TornFactionCrimeVO> ocList) {
        if (CollectionUtils.isEmpty(ocList)) {
            return;
        }

        List<Long> ocIdList = ocList.stream().map(TornFactionCrimeVO::getId).toList();
        List<TornFactionOcDO> oldDataList = ocDao.queryListByIdList(factionId, ocIdList);
        for (TornFactionCrimeVO oc : ocList) {
            LocalDateTime readyTime = DateTimeUtils.convertToDateTime(oc.getReadyAt());
            boolean isDiff = oldDataList.stream().noneMatch(old ->
                    old.getId().equals(oc.getId()) && readyTime != null && readyTime.equals(old.getReadyTime()));
            if (isDiff) {
                ocDao.lambdaUpdate()
                        .set(TornFactionOcDO::getReadyTime, readyTime)
                        .set(TornFactionOcDO::getStatus, oc.getStatus())
                        .eq(TornFactionOcDO::getId, oc.getId())
                        .update();
            }
        }
        slotManager.updateOcSlot(ocList, oldDataList);
    }

    /**
     * 更新完成OC
     */
    public void updateCompleteData(TornFactionCrimeVO oc, List<TornFactionOcSlotDO> slotList) {
        Map<Integer, TornItemsDO> itemMap = itemsManager.getMap();
        ocDao.lambdaUpdate()
                .set(TornFactionOcDO::getStatus, oc.getStatus())
                .set(TornFactionOcDO::getExecutedTime, DateTimeUtils.convertToDateTime(oc.getExecutedAt()))
                .set(TornFactionOcDO::getRewardMoney, oc.getRewardMoney())
                .set(TornFactionOcDO::getRewardItems, oc.getRewardItems())
                .set(TornFactionOcDO::getRewardItemsValue, oc.getRewardItemsValue(itemMap))
                .eq(TornFactionOcDO::getId, oc.getId())
                .update();

        for (TornFactionCrimeSlotVO slot : oc.getSlots()) {
            TornFactionOcSlotDO execSlot = slotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId()))
                    .filter(s -> slot.getUserId().equals(s.getUserId()))
                    .findAny().orElse(null);
            if (execSlot == null) {
                continue;
            }

            slotDao.lambdaUpdate()
                    .set(TornFactionOcSlotDO::getProgress, slot.getUser().getProgress())
                    .set(TornFactionOcSlotDO::getOutcomeItemId, slot.getOutcomeItemId())
                    .set(TornFactionOcSlotDO::getOutcomeItemStatus, slot.getOutcomeItemStatus())
                    .set(TornFactionOcSlotDO::getOutcomeItemValue, slot.getOutcomeItemValue(itemMap))
                    .eq(TornFactionOcSlotDO::getId, execSlot.getId())
                    .update();
        }
    }
}