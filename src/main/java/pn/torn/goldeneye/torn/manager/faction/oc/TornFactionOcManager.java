package pn.torn.goldeneye.torn.manager.faction.oc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcNoticeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcNoticeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeSlotVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.torn.TornOcUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * OC公共逻辑层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.08
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcManager {
    private final TornFactionOcUserManager ocUserManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final TornFactionOcNoticeDAO noticeDao;
    private final SysSettingDAO settingDao;

    /**
     * 更新OC数据
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateOc(List<TornFactionCrimeVO> ocList) {
        if (CollectionUtils.isEmpty(ocList)) {
            return;
        }

        List<TornFactionOcNoticeDO> skipList = noticeDao.querySkipList();
        List<TornFactionCrimeVO> newDataList = new ArrayList<>();
        List<Long> ocIdList = ocList.stream().map(TornFactionCrimeVO::getId).toList();
        List<Long> validOcIdList = new ArrayList<>();
        List<TornFactionOcDO> oldDataList = ocDao.queryListByIdList(ocIdList);
        for (TornFactionCrimeVO oc : ocList) {
            if (oc.getSlots().stream().noneMatch(s -> s.getUser() != null)) {
                continue;
            }

            validOcIdList.add(oc.getId());
            if (oldDataList.stream().anyMatch(r -> r.getId().equals(oc.getId()))) {
                updateOcData(oc, checkTodayPlanning(skipList, oc));
            } else {
                newDataList.add(oc);
            }
        }

        insertOcData(newDataList, skipList);
        completeOcData(validOcIdList);
        ocUserManager.updateJoinedUserPassRate(ocList);
    }

    /**
     * 插入新OC
     */
    public void insertOcData(List<TornFactionCrimeVO> ocList, List<TornFactionOcNoticeDO> skipList) {
        if (ocList.isEmpty()) {
            return;
        }

        List<TornFactionOcDO> dataList = ocList.stream().map(oc -> oc.convert2DO(checkTodayPlanning(skipList, oc))).toList();
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
    public void updateOcData(TornFactionCrimeVO oc, boolean isCurrent) {
        ocDao.lambdaUpdate()
                .set(oc.getReadyAt() != null, TornFactionOcDO::getReadyTime,
                        DateTimeUtils.convertToDateTime(oc.getReadyAt()))
                .set(TornFactionOcDO::getStatus, oc.getStatus())
                .set(TornFactionOcDO::isHasCurrent, isCurrent)
                .eq(TornFactionOcDO::getId, oc.getId())
                .update();

        for (TornFactionCrimeSlotVO slot : oc.getSlots()) {
            if (slot.getUser() != null) {
                slotDao.lambdaUpdate()
                        .set(TornFactionOcSlotDO::getUserId, slot.getUser().getId())
                        .set(TornFactionOcSlotDO::getJoinTime, DateTimeUtils.convertToDateTime(slot.getUser().getJoinedAt()))
                        .set(TornFactionOcSlotDO::getPassRate, slot.getCheckpointPassRate())
                        .eq(TornFactionOcSlotDO::getOcId, oc.getId())
                        .eq(TornFactionOcSlotDO::getPosition, slot.getPosition() + "#" + slot.getPositionNumber())
                        .update();
            } else {
                slotDao.lambdaUpdate()
                        .set(TornFactionOcSlotDO::getUserId, null)
                        .set(TornFactionOcSlotDO::getJoinTime, null)
                        .set(TornFactionOcSlotDO::getPassRate, null)
                        .eq(TornFactionOcSlotDO::getOcId, oc.getId())
                        .eq(TornFactionOcSlotDO::getPosition, slot.getPosition() + "#" + slot.getPositionNumber())
                        .update();
            }
        }
    }

    /**
     * 已执行的OC设为完成状态
     */
    public void completeOcData(List<Long> validOcIdList) {
        List<TornFactionOcDO> completedList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getStatus, TornOcStatusEnum.PLANNING.getCode())
                .lt(TornFactionOcDO::getReadyTime, LocalDateTime.now())
                .list();
        for (TornFactionOcDO oc : completedList) {
            ocDao.updateCompleted(oc.getId());
        }

        if (!CollectionUtils.isEmpty(validOcIdList)) {
            List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                    .notIn(TornFactionOcDO::getId, validOcIdList)
                    .ne(TornFactionOcDO::getStatus, TornOcStatusEnum.COMPLETED.getCode())
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
     * 刷新轮转队配置
     *
     * @param planKey        计划队伍配置Key
     * @param excludePlanKey 排除的计划队伍配置Key
     * @param recKey         招募队伍配置Key
     * @param excludeRecKey  排除的招募队伍配置Key
     * @param rank           查询级别
     */
    public void refreshRotationSetting(String planKey, String excludePlanKey, String recKey, String excludeRecKey,
                                       int... rank) {
        List<TornFactionOcDO> planList = ocDao.queryListByStatusAndRank(TornOcStatusEnum.PLANNING, rank);
        TornFactionOcDO planOc = buildRotationList(planList, 0L, excludePlanKey, excludePlanKey).get(0);
        settingDao.updateSetting(planKey, planOc.getId().toString());

        List<TornFactionOcDO> allRecList = ocDao.queryListByStatusAndRank(TornOcStatusEnum.RECRUITING, rank);
        List<TornFactionOcDO> recList = buildRotationList(allRecList, planOc.getId(), excludePlanKey, excludeRecKey);
        String recIds = String.join(",", recList.stream().map(s -> s.getId().toString()).toList());
        settingDao.updateSetting(recKey, recIds);
    }

    /**
     * 获取轮转队招募中的OC列表
     *
     * @param planOcId       计划OC ID
     * @param excludePlanKey 排除计划队的设置Key
     * @param excludeRecKey  排除招募队的设置Key
     * @param rank           包含的级别
     */
    public List<TornFactionOcDO> queryRotationRecruitList(long planOcId, String excludePlanKey, String excludeRecKey,
                                                          int... rank) {
        List<TornFactionOcDO> recList = ocDao.lambdaQuery()
                .in(TornFactionOcDO::getRank, Arrays.stream(rank).boxed().toList())
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.RECRUITING.getCode(), TornOcStatusEnum.PLANNING.getCode())
                .list();
        return buildRotationList(recList, planOcId, excludePlanKey, excludeRecKey);
    }

    /**
     * 检查是否启用临时轮转
     */
    public boolean isCheckEnableTemp() {
        String tempSetting = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_TEMP_ENABLE);
        return "true".equals(tempSetting);
    }

    /**
     * 检测OC是否需要跳过校准
     *
     * @return true为跳过
     */
    private boolean checkTodayPlanning(List<TornFactionOcNoticeDO> skipList, TornFactionCrimeVO oc) {
        boolean isRotationOc = TornOcUtils.isRotationOc(oc, oc.getSlots(), skipList);
        String tempEnableStr = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_TEMP_ENABLE);
        boolean isTempEnable = "true".equals(tempEnableStr);
        String tempIdStr = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_PLAN_ID + "TEMP");
        boolean isTemp = isTempEnable && StringUtils.hasText(tempIdStr) && oc.getId().equals(Long.parseLong(tempIdStr));
        return isRotationOc && !isTemp && TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus());
    }

    /**
     * 构建轮转队列表
     *
     * @param planOcId          计划OC ID
     * @param excludeSettingKey 排除这些队伍的设置Key
     */
    private List<TornFactionOcDO> buildRotationList(List<TornFactionOcDO> ocList, long planOcId,
                                                    String excludePlanKey, String excludeSettingKey) {
        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(ocList);
        List<TornFactionOcNoticeDO> skipList = noticeDao.querySkipList();

        List<Long> excludeIdList = new ArrayList<>();
        excludeIdList.add(Long.parseLong(settingDao.querySettingValue(excludePlanKey)));
        String excludeIds = settingDao.querySettingValue(excludeSettingKey);
        excludeIdList.addAll(Arrays.stream(excludeIds.split(",")).map(Long::parseLong).toList());

        List<TornFactionOcDO> resultList = new ArrayList<>();
        for (TornFactionOcDO oc : ocList) {
            List<TornFactionOcSlotDO> currentSlotList = slotList.stream().filter(s ->
                    s.getOcId().equals(oc.getId())).toList();
            boolean isRotationOc = TornOcUtils.isRotationOc(oc, currentSlotList, skipList);
            if (!isRotationOc || oc.getId().equals(planOcId) || excludeIdList.contains(oc.getId())) {
                continue;
            }

            resultList.add(oc);
        }

        return resultList;
    }
}