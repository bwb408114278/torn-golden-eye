package pn.torn.goldeneye.torn.manager.faction.oc;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OC用户公共逻辑层
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.01
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcUserManager {
    private final TornFactionOcUserDAO ocUserDao;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;

    /**
     * 查询可参加OC的替补人员
     *
     * @param rank OC级别
     * @return 用户ID列表
     */
    public Set<Long> findFreeUser(int rank, List<TornFactionOcSlotDO> planningSlotList) {
        List<TornFactionOcUserDO> userList = ocUserDao.lambdaQuery().eq(TornFactionOcUserDO::getRank, rank).list();
        userList.removeIf(u -> u.getPassRate().compareTo(60) < 1);
        Set<Long> userIdSet = userList.stream().map(TornFactionOcUserDO::getUserId).collect(Collectors.toSet());

        List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getStatus, TornOcStatusEnum.RECRUITING.getCode())
                .list();
        if (CollectionUtils.isEmpty(ocList)) {
            return userIdSet;
        }
        // 移除已经参加了OC的人
        List<TornFactionOcSlotDO> slotList = slotDao.lambdaQuery()
                .in(TornFactionOcSlotDO::getOcId, ocList.stream().map(TornFactionOcDO::getId).toList())
                .list();
        Set<Long> joinedUserSet = planningSlotList.stream().map(TornFactionOcSlotDO::getUserId).collect(Collectors.toSet());
        joinedUserSet.addAll(slotList.stream().map(TornFactionOcSlotDO::getUserId).collect(Collectors.toSet()));
        userIdSet.removeIf(joinedUserSet::contains);

        return userIdSet;
    }
}