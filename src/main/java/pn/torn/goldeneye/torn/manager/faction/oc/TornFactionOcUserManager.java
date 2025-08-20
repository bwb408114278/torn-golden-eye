package pn.torn.goldeneye.torn.manager.faction.oc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcNoticeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcNoticeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;

import java.util.Arrays;
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
    private final TornFactionOcNoticeDAO noticeDao;

    /**
     * 查询可参加OC的替补人员
     *
     * @param rank OC级别
     * @return 用户ID列表
     */
    public Set<Long> findRotationUser(int... rank) {
        List<TornFactionOcUserDO> userList = findFreeUser(null, rank);
        List<TornFactionOcNoticeDO> skipList = noticeDao.lambdaQuery()
                .in(TornFactionOcNoticeDO::getRank, Arrays.stream(rank).boxed().toList())
                .and(wrapper -> wrapper
                        .eq(TornFactionOcNoticeDO::getHasNotice, false)
                        .or()
                        .eq(TornFactionOcNoticeDO::getHasSkip, true))
                .list();

        userList.removeIf(u -> {
            if (u.getPassRate().compareTo(60) < 0) {
                return true;
            }
            // 如果多个级别要提醒，当这些级别都屏蔽时才不提醒
            List<TornFactionOcNoticeDO> blockList = skipList.stream()
                    .filter(s -> s.getUserId().equals(u.getUserId()))
                    .toList();
            return blockList.size() == rank.length;
        });
        return userList.stream().map(TornFactionOcUserDO::getUserId).collect(Collectors.toSet());
    }

    /**
     * 查询可参加OC的替补人员
     *
     * @param rank OC级别
     */
    public List<TornFactionOcUserDO> findFreeUser(String position, int... rank) {
        List<TornFactionOcUserDO> userList = ocUserDao.lambdaQuery()
                .in(TornFactionOcUserDO::getRank, Arrays.stream(rank).boxed().toList())
                .eq(position != null, TornFactionOcUserDO::getPosition, position)
                .orderByDesc(TornFactionOcUserDO::getPassRate)
                .page(new Page<>(1, 10))
                .getRecords();

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
}