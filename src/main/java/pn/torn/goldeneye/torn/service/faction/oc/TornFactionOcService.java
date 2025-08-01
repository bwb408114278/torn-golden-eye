package pn.torn.goldeneye.torn.service.faction.oc;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSkipDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSkipDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeSlotVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Torn Oc逻辑层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Service
@RequiredArgsConstructor
public class TornFactionOcService {
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final TornFactionOcUserDAO userDao;
    private final TornFactionOcSkipDAO skipDao;
    private final OcNoticeService ocNoticeService;
    private final DynamicTaskService taskService;

    /**
     * 更新OC数据
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateOc(List<TornFactionCrimeVO> ocList) {
        if (CollectionUtils.isEmpty(ocList)) {
            return;
        }

        List<TornFactionOcSkipDO> allSkipList = skipDao.list();
        List<Long> ocIdList = ocList.stream().map(TornFactionCrimeVO::getId).toList();
        List<TornFactionOcDO> oldDataList = ocDao.lambdaQuery().in(TornFactionOcDO::getId, ocIdList).list();
        List<TornFactionCrimeVO> newDataList = new ArrayList<>();
        for (TornFactionCrimeVO oc : ocList) {
            if (checkOcSkip(allSkipList, oc)) {
                continue;
            }

            if (oldDataList.stream().anyMatch(r -> r.getId().equals(oc.getId()))) {
                updateOcData(oc);
            } else {
                newDataList.add(oc);
            }
        }

        insertOcData(newDataList);
        updateUserPassRate(ocList);
        updateScheduleTask(allSkipList, ocList);
    }

    /**
     * 插入新OC
     */
    public void insertOcData(List<TornFactionCrimeVO> ocList) {
        if (ocList.isEmpty()) {
            return;
        }

        List<TornFactionOcDO> dataList = ocList.stream().map(TornFactionCrimeVO::convert2DO).toList();
        ocDao.saveBatch(dataList);

        List<TornFactionOcSlotDO> slotList = new ArrayList<>();
        for (TornFactionCrimeVO oc : ocList) {
            slotList.addAll(oc.getSlots().stream().map(s -> s.convert2SlotDO(oc.getId())).toList());
        }
        slotDao.saveBatch(slotList);
    }

    /**
     * 更新OC
     */
    public void updateOcData(TornFactionCrimeVO oc) {
        if (oc.getReadyAt() != null) {
            ocDao.lambdaUpdate()
                    .set(TornFactionOcDO::getReadyTime, DateTimeUtils.convertToDateTime(oc.getReadyAt()))
                    .eq(TornFactionOcDO::getId, oc.getId())
                    .update();
        }

        for (TornFactionCrimeSlotVO slot : oc.getSlots()) {
            if (slot.getUser() != null) {
                slotDao.lambdaUpdate()
                        .set(TornFactionOcSlotDO::getUserId, slot.getUser().getId())
                        .set(TornFactionOcSlotDO::getJoinTime, DateTimeUtils.convertToDateTime(slot.getUser().getJoinedAt()))
                        .set(TornFactionOcSlotDO::getPassRate, slot.getCheckpointPassRate())
                        .eq(TornFactionOcSlotDO::getOcId, oc.getId())
                        .eq(TornFactionOcSlotDO::getPosition, slot.getPosition() + "#" + slot.getPositionNumber())
                        .update();
            }
        }
    }

    /**
     * 更新用户成功率
     */
    public void updateUserPassRate(List<TornFactionCrimeVO> ocList) {
        List<TornFactionOcUserDO> allUserList = userDao.list();
        List<TornFactionOcUserDO> newDataList = new ArrayList<>();

        for (TornFactionCrimeVO oc : ocList) {
            for (TornFactionCrimeSlotVO slot : oc.getSlots()) {
                if (slot.getUser() == null) {
                    continue;
                }

                TornFactionOcUserDO oldData = allUserList.stream().filter(u ->
                        u.getUserId().equals(slot.getUser().getId()) &&
                                u.getRank().equals(oc.getDifficulty()) &&
                                u.getOcName().equals(oc.getName()) &&
                                u.getPosition().equals(slot.getPosition())).findAny().orElse(null);
                if (oldData != null && !oldData.getPassRate().equals(slot.getCheckpointPassRate())) {
                    userDao.lambdaUpdate()
                            .set(TornFactionOcUserDO::getPassRate, slot.getCheckpointPassRate())
                            .eq(TornFactionOcUserDO::getId, oldData.getId())
                            .update();
                } else if (oldData == null) {
                    newDataList.add(slot.convert2UserDO(oc.getDifficulty(), oc.getName()));
                }
            }
        }

        if (!newDataList.isEmpty()) {
            userDao.saveBatch(newDataList);
        }
    }

    /**
     * 更新定时提醒
     */
    private void updateScheduleTask(List<TornFactionOcSkipDO> allSkipList, List<TornFactionCrimeVO> ocList) {
        for (TornFactionCrimeVO oc : ocList) {
            if (checkOcSkip(allSkipList, oc) || !oc.getStatus().equals(TornOcStatusEnum.PLANNING.getCode())) {
                continue;
            }

            taskService.updateTask("oc-join-" + oc.getDifficulty(),
                    ocNoticeService.buildNotice(oc.getDifficulty()),
                    DateTimeUtils.convertToInstant(LocalDateTime.now().plusMinutes(-5)), null);
        }
    }

    /**
     * 检测OC是否需要跳过校准
     *
     * @return true为跳过
     */
    private boolean checkOcSkip(List<TornFactionOcSkipDO> allSkipList, TornFactionCrimeVO oc) {
        boolean notRotationRank = !oc.getDifficulty().equals(8) && !oc.getDifficulty().equals(7);
        boolean isChainOc = oc.getDifficulty().equals(8) && oc.getName().equals(TornConstants.OC_RANK_8_CHAIN);
        if (notRotationRank || isChainOc) {
            return true;
        }

        if (oc.getSlots().stream().noneMatch(s -> s.getUser() != null)) {
            return true;
        }

        for (TornFactionCrimeSlotVO slot : oc.getSlots()) {
            if (slot.getUser() == null) {
                continue;
            }

            boolean isSkip = allSkipList.stream().anyMatch(p ->
                    p.getUserId().equals(slot.getUser().getId()) &&
                            p.getRank().equals(oc.getDifficulty()));
            if (isSkip) {
                return true;
            }
        }

        return false;
    }
}