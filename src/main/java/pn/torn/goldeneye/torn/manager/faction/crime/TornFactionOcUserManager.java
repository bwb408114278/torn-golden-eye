package pn.torn.goldeneye.torn.manager.faction.crime;

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
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeSlotVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeUserVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OC用户公共逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.01
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcUserManager {
    private final TornFactionOcUserDAO ocUserDao;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;

    /**
     * 更新空闲用户成功率
     */
    public void updateEmptyUserPassRate(long factionId, long userId, List<TornFactionCrimeVO> ocList) {
        List<TornFactionOcUserDO> ocUserList = ocUserDao.lambdaQuery().eq(TornFactionOcUserDO::getUserId, userId).list();
        updateUserPassRateData(factionId, ocList, ocUserList, new UpdatePassRateCallback() {
            @Override
            public boolean checkUserCorrect(TornFactionCrimeUserVO user) {
                return user == null || user.getId().equals(userId);
            }

            @Override
            public long getUserId(TornFactionCrimeUserVO user) {
                return userId;
            }
        });
    }

    /**
     * 更新已加入用户成功率
     */
    public void updateJoinedUserPassRate(long factionId, List<TornFactionCrimeVO> ocList) {
        List<TornFactionOcUserDO> ocUserList = ocUserDao.list();
        updateUserPassRateData(factionId, ocList, ocUserList, new UpdatePassRateCallback() {
            @Override
            public boolean checkUserCorrect(TornFactionCrimeUserVO user) {
                return user != null;
            }

            @Override
            public long getUserId(TornFactionCrimeUserVO user) {
                return user.getId();
            }
        });
    }

    /**
     * 更新已加入用户成功率
     */
    public void updateUserPassRateData(long factionId, List<TornFactionCrimeVO> ocList,
                                       List<TornFactionOcUserDO> allUserList, UpdatePassRateCallback callback) {
        List<TornFactionOcUserDO> newDataList = new ArrayList<>();
        for (TornFactionCrimeVO oc : ocList) {
            for (TornFactionCrimeSlotVO slot : oc.getSlots()) {
                TornFactionCrimeUserVO slotUser = slot.getUser();
                if (!callback.checkUserCorrect(slotUser)) {
                    continue;
                }

                TornFactionOcUserDO oldData = allUserList.stream().filter(u ->
                        u.getUserId().equals(callback.getUserId(slotUser)) &&
                                u.getRank().equals(oc.getDifficulty()) &&
                                u.getOcName().equals(oc.getName()) &&
                                u.getPosition().equals(slot.getPosition())).findAny().orElse(null);
                if (oldData != null && oldData.getPassRate().compareTo(slot.getCheckpointPassRate()) < 0) {
                    ocUserDao.lambdaUpdate()
                            .set(TornFactionOcUserDO::getPassRate, slot.getCheckpointPassRate())
                            .eq(TornFactionOcUserDO::getId, oldData.getId())
                            .update();
                } else if (oldData == null) {
                    fillNewData(factionId, oc, slot, newDataList, callback);
                }
            }
        }

        if (!newDataList.isEmpty()) {
            ocUserDao.saveBatch(newDataList);
        }
    }

    /**
     * 查询可参加OC的替补人员
     *
     * @param rank OC级别
     */
    public List<TornFactionOcUserDO> findFreeUser(String position, long factionId, int... rank) {
        List<TornFactionOcUserDO> userList = ocUserDao.lambdaQuery()
                .in(TornFactionOcUserDO::getRank, Arrays.stream(rank).boxed().toList())
                .eq(position != null, TornFactionOcUserDO::getPosition, position)
                .eq(TornFactionOcUserDO::getFactionId, factionId)
                .orderByDesc(TornFactionOcUserDO::getPassRate)
                .list();

        List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.RECRUITING.getCode(), TornOcStatusEnum.PLANNING.getCode())
                .list();
        if (CollectionUtils.isEmpty(ocList)) {
            return userList;
        }
        // 移除已经参加了OC的人
        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(ocList);
        Set<Long> joinedUserSet = slotList.stream().map(TornFactionOcSlotDO::getUserId).collect(Collectors.toSet());
        userList.removeIf(u -> joinedUserSet.contains(u.getUserId()));

        return userList;
    }

    /**
     * 填充新数据
     */
    private void fillNewData(long factionId, TornFactionCrimeVO oc, TornFactionCrimeSlotVO slot,
                             List<TornFactionOcUserDO> newDataList, UpdatePassRateCallback callback) {
        TornFactionOcUserDO newData = slot.convert2UserDO(
                callback.getUserId(slot.getUser()), factionId, oc.getDifficulty(), oc.getName());
        if (!newDataList.contains(newData)) {
            newDataList.add(newData);
        }
    }

    /**
     * 更新用户成功率回调
     */
    public interface UpdatePassRateCallback {
        /**
         * 检查用户是否正确，返回false将不更新该岗位用户数据
         *
         * @param user OC已加入用户
         * @return false将跳过该数据
         */
        boolean checkUserCorrect(TornFactionCrimeUserVO user);

        /**
         * 获取用户ID
         *
         * @param user OC已加入用户
         * @return 用户ID
         */
        long getUserId(TornFactionCrimeUserVO user);
    }
}