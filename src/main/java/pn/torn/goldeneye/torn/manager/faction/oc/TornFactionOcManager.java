package pn.torn.goldeneye.torn.manager.faction.oc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.torn.TornOcUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final SysSettingDAO settingDao;

    /**
     * 更新OC数据
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateOc(long factionId, List<TornFactionCrimeVO> ocList) {
        if (CollectionUtils.isEmpty(ocList)) {
            return;
        }

        List<TornFactionCrimeVO> newDataList = new ArrayList<>();
        List<TornFactionCrimeVO> updateDataList = new ArrayList<>();
        List<Long> ocIdList = ocList.stream().map(TornFactionCrimeVO::getId).toList();
        List<Long> validOcIdList = new ArrayList<>();
        List<TornFactionOcDO> oldDataList = ocDao.queryListByIdList(ocIdList);
        for (TornFactionCrimeVO oc : ocList) {
            if (oc.getSlots().stream().noneMatch(s -> s.getUser() != null)) {
                continue;
            }

            validOcIdList.add(oc.getId());
            if (oldDataList.stream().anyMatch(r -> r.getId().equals(oc.getId()))) {
                updateDataList.add(oc);
            } else {
                newDataList.add(oc);
            }
        }

        insertOcData(factionId, newDataList);
        updateOcData(updateDataList);
        completeOcData(factionId, validOcIdList);
        ocUserManager.updateJoinedUserPassRate(factionId, ocList);
    }

    /**
     * 插入新OC
     */
    public void insertOcData(long factionId, List<TornFactionCrimeVO> ocList) {
        if (ocList.isEmpty()) {
            return;
        }

        List<TornFactionOcDO> dataList = ocList.stream().map(oc -> oc.convert2DO(factionId)).toList();
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
    public void updateOcData(List<TornFactionCrimeVO> ocList) {
        if (CollectionUtils.isEmpty(ocList)) {
            return;
        }

        List<Long> ocIdList = ocList.stream().map(TornFactionCrimeVO::getId).toList();
        List<TornFactionOcDO> oldDataList = ocDao.queryListByIdList(ocIdList);
        for (TornFactionCrimeVO oc : ocList) {
            LocalDateTime readyTime = DateTimeUtils.convertToDateTime(oc.getReadyAt());
            boolean isDiff = oldDataList.stream().noneMatch(old -> old.getId().equals(oc.getId()) &&
                    old.getStatus().equals(oc.getStatus()) && old.getReadyTime().equals(readyTime));
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
     * 已执行的OC设为完成状态
     */
    public void completeOcData(long factionId, List<Long> validOcIdList) {
        List<TornFactionOcDO> completedList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getStatus, TornOcStatusEnum.PLANNING.getCode())
                .lt(TornFactionOcDO::getReadyTime, LocalDateTime.now())
                .list();
        for (TornFactionOcDO oc : completedList) {
            ocDao.updateCompleted(oc.getId());
        }

        if (!CollectionUtils.isEmpty(validOcIdList)) {
            List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                    .eq(TornFactionOcDO::getFactionId, factionId)
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
    public void refreshRotationSetting(long factionId, String planKey, String excludePlanKey, String recKey, String excludeRecKey,
                                       int... rank) {
        List<TornFactionOcDO> planList = ocDao.queryListByStatusAndRank(factionId, TornOcStatusEnum.PLANNING, rank);
        TornFactionOcDO planOc = buildRotationList(planList, 0L, excludePlanKey, excludePlanKey).get(0);
        settingDao.updateSetting(planKey, planOc.getId().toString());

        List<TornFactionOcDO> allRecList = ocDao.queryListByStatusAndRank(factionId, TornOcStatusEnum.RECRUITING, rank);
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
    public List<TornFactionOcDO> queryRotationRecruitList(long planOcId, long factionId,
                                                          String excludePlanKey, String excludeRecKey, int... rank) {
        List<TornFactionOcDO> recList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, factionId)
                .in(TornFactionOcDO::getRank, Arrays.stream(rank).boxed().toList())
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.RECRUITING.getCode(), TornOcStatusEnum.PLANNING.getCode())
                .list();
        return buildRotationList(recList, planOcId, excludePlanKey, excludeRecKey);
    }

    /**
     * 构建轮转队列表
     *
     * @param planOcId          计划OC ID
     * @param excludeSettingKey 排除这些队伍的设置Key
     */
    private List<TornFactionOcDO> buildRotationList(List<TornFactionOcDO> ocList, long planOcId,
                                                    String excludePlanKey, String excludeSettingKey) {
        List<Long> excludeIdList = new ArrayList<>();
        excludeIdList.add(Long.parseLong(settingDao.querySettingValue(excludePlanKey)));
        String excludeIds = settingDao.querySettingValue(excludeSettingKey);
        excludeIdList.addAll(NumberUtils.splitToLongList(excludeIds));

        List<TornFactionOcDO> resultList = new ArrayList<>();
        for (TornFactionOcDO oc : ocList) {
            boolean isRotationOc = TornOcUtils.isRotationOc(oc);
            if (!isRotationOc || oc.getId().equals(planOcId) || excludeIdList.contains(oc.getId())) {
                continue;
            }

            resultList.add(oc);
        }

        return resultList;
    }
}