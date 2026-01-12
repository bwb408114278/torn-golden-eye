package pn.torn.goldeneye.torn.service.faction.oc.create;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.create.OcNewTeamBO;

import java.time.LocalDateTime;
import java.util.*;

/**
 * OC管理服务
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.11.03
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornOcManageService {
    private final TornSettingOcSlotManager settingOcSlotManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final TornFactionOcUserDAO ocUserDao;

    /**
     * 核心分析方法
     */
    public OcNewTeamBO analyze(long factionId, LocalDateTime now, LocalDateTime targetTime) {
        List<TornFactionOcDO> ocList = ocDao.queryExecutingOc(factionId).stream()
                .filter(o -> TornConstants.ROTATION_OC_NAME.contains(o.getName()))
                .toList();
        List<TornFactionOcDO> occupyList = new ArrayList<>();
        List<TornFactionOcDO> finishList = new ArrayList<>();
        List<TornFactionOcDO> stopList = new ArrayList<>();
        for (TornFactionOcDO oc : ocList) {
            if (TornOcStatusEnum.PLANNING.getCode().equals(oc.getStatus()) && targetTime.isAfter(oc.getReadyTime())) {
                finishList.add(oc);
            } else if (oc.getReadyTime() == null || targetTime.isAfter(oc.getReadyTime())) {
                stopList.add(oc);
            } else {
                occupyList.add(oc);
            }
        }

        List<TornSettingOcSlotDO> settingList = getSettingList();
        Set<Long> availableUser = queryAvailableUser(factionId, settingList);
        List<TornFactionOcUserDO> freeUserList = queryFreeUser(factionId, availableUser, occupyList, stopList);
        OcNewTeamBO result = new OcNewTeamBO(ocList, availableUser, freeUserList, stopList, finishList);

        Map<TornFactionOcDO, List<TornFactionOcSlotDO>> emptySlotMap = getEmptySlotMap(factionId, now, result);
        assignEmptySlot(emptySlotMap, freeUserList, settingList, result);
        setLowLevelUser(freeUserList, settingList, result);
        return result;
    }

    /**
     * 分配已有的空岗位，会移除已分配的用户
     */
    private void assignEmptySlot(Map<TornFactionOcDO, List<TornFactionOcSlotDO>> emptySlotMap,
                                 List<TornFactionOcUserDO> userList, List<TornSettingOcSlotDO> settingList,
                                 OcNewTeamBO result) {
        Set<Long> assignedUserSet = new HashSet<>();
        List<TornFactionOcDO> failMatchList = new ArrayList<>();
        for (Map.Entry<TornFactionOcDO, List<TornFactionOcSlotDO>> entry : emptySlotMap.entrySet()) {
            for (TornSettingOcSlotDO setting : settingList) {
                TornFactionOcSlotDO slot = entry.getValue().stream()
                        .filter(s -> s.getPosition().equals(setting.getSlotCode()))
                        .findAny().orElse(null);
                if (slot != null) {
                    List<TornFactionOcUserDO> matchUserList = matchUser(userList, setting).stream()
                            .filter(u -> !assignedUserSet.contains(u.getUserId()))
                            .sorted(Comparator.comparing(TornFactionOcUserDO::getPassRate))
                            .toList();
                    if (!CollectionUtils.isEmpty(matchUserList)) {
                        assignedUserSet.add(matchUserList.getFirst().getUserId());
                    } else {
                        failMatchList.add(entry.getKey());
                    }

                    break;
                }
            }
        }

        userList.removeIf(u -> assignedUserSet.contains(u.getUserId()));
        result.afterMatch(assignedUserSet, failMatchList);
    }

    /**
     * 获取只能做低级的用户
     */
    private void setLowLevelUser(List<TornFactionOcUserDO> userList, List<TornSettingOcSlotDO> settingList,
                                 OcNewTeamBO result) {
        Set<Long> matchUserSet = new HashSet<>();
        for (TornSettingOcSlotDO setting : settingList) {
            if (setting.getRank().equals(7)) {
                continue;
            }

            List<TornFactionOcUserDO> matchUserList = matchUser(userList, setting);
            matchUserSet.addAll(matchUserList.stream().map(TornFactionOcUserDO::getUserId).toList());
        }

        result.setHighAbility(matchUserSet);
    }

    /**
     * 获取OC岗位配置列表，以级别、权重正序排序
     */
    private List<TornSettingOcSlotDO> getSettingList() {
        return settingOcSlotManager.getList().stream()
                .filter(s -> TornConstants.ROTATION_OC_NAME.contains(s.getOcName()))
                .sorted(Comparator.comparing(TornSettingOcSlotDO::getRank)
                        .thenComparing(TornSettingOcSlotDO::getPriority))
                .toList();
    }

    /**
     * 获取空闲岗位的OC Map
     */
    private Map<TornFactionOcDO, List<TornFactionOcSlotDO>> getEmptySlotMap(long factionId, LocalDateTime now,
                                                                            OcNewTeamBO result) {
        List<TornFactionOcDO> ocList = ocDao.queryRecrutList(factionId);
        List<TornFactionOcDO> todayStopList = ocList.stream()
                .filter(o -> o.getReadyTime() == null || now.plusDays(1L).isAfter(o.getReadyTime()))
                .toList();

        List<TornFactionOcSlotDO> emptySlotList = slotDao.queryEmptySlotList(todayStopList);
        Map<TornFactionOcDO, List<TornFactionOcSlotDO>> resultMap = HashMap.newHashMap(todayStopList.size());
        for (TornFactionOcDO oc : todayStopList) {
            List<TornFactionOcSlotDO> slotList = emptySlotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId())).toList();
            resultMap.put(oc, slotList);
        }

        result.setTodayStop(todayStopList);
        return resultMap;
    }

    /**
     * 查询可用用户
     */
    private Set<Long> queryAvailableUser(long factionId, List<TornSettingOcSlotDO> settingList) {
        List<TornFactionOcUserDO> ocUserList = ocUserDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getFactionId, factionId)
                .in(TornFactionOcUserDO::getOcName, TornConstants.ROTATION_OC_NAME)
                .list();

        Set<Long> resultSet = new HashSet<>();
        for (TornSettingOcSlotDO setting : settingList) {
            List<TornFactionOcUserDO> matchUserList = matchUser(ocUserList, setting);
            resultSet.addAll(matchUserList.stream().map(TornFactionOcUserDO::getUserId).toList());
        }

        return resultSet;
    }

    /**
     * 查询可用的空闲用户
     */
    private List<TornFactionOcUserDO> queryFreeUser(long factionId, Set<Long> availableUser,
                                                    List<TornFactionOcDO> occupyList, List<TornFactionOcDO> stopList) {
        List<TornFactionOcDO> ocList = new ArrayList<>(occupyList);
        ocList.addAll(stopList);

        List<TornFactionOcSlotDO> slotList = CollectionUtils.isEmpty(ocList) ?
                new ArrayList<>() : slotDao.queryListByOc(ocList);
        List<Long> occupyUserList = slotList.stream()
                .map(TornFactionOcSlotDO::getUserId)
                .filter(Objects::nonNull)
                .toList();

        return ocUserDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getFactionId, factionId)
                .in(TornFactionOcUserDO::getOcName, TornConstants.ROTATION_OC_NAME)
                .in(TornFactionOcUserDO::getUserId, availableUser)
                .notIn(!CollectionUtils.isEmpty(occupyUserList), TornFactionOcUserDO::getUserId, occupyUserList)
                .list();
    }

    /**
     * 匹配用户列表
     */
    private List<TornFactionOcUserDO> matchUser(List<TornFactionOcUserDO> userList, TornSettingOcSlotDO setting) {
        return userList.stream()
                .filter(u -> u.getOcName().equals(setting.getOcName()))
                .filter(u -> u.getPosition().equals(setting.getSlotShortCode()))
                .filter(u -> u.getPassRate().compareTo(setting.getPassRate()) >= 0)
                .toList();
    }
}