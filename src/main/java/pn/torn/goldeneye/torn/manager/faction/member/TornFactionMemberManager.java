package pn.torn.goldeneye.torn.manager.faction.member;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;

import java.util.ArrayList;
import java.util.List;

/**
 * Torn帮派成员公共逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.09
 */
@Component
@RequiredArgsConstructor
public class TornFactionMemberManager {
    private final TornUserDAO userDao;
    private final TornFactionOcUserDAO ocUserDao;

    /**
     * 爬取帮派成员
     */
    public void updateFactionMember(long factionId, TornFactionMemberListVO memberList) {
        List<TornUserDO> newUserList = new ArrayList<>();

        List<Long> userIdList = memberList.getMembers().stream().map(TornFactionMemberVO::getId).toList();
        List<TornUserDO> userList = userDao.lambdaQuery().in(TornUserDO::getId, userIdList).list();

        for (TornFactionMemberVO member : memberList.getMembers()) {
            TornUserDO oldData = userList.stream().filter(u -> u.getId().equals(member.getId())).findAny().orElse(null);
            TornUserDO newData = member.convert2DO(factionId);

            if (oldData == null) {
                newUserList.add(newData);
            } else if (!oldData.equals(newData)) {
                userDao.updateById(newData);
                ocUserDao.updateUserFaction(factionId, newData.getId());
            }
        }

        if (!CollectionUtils.isEmpty(newUserList)) {
            userDao.saveBatch(newUserList);
        }
        removeFactionMember(factionId, userIdList);
    }

    /**
     * 移除不在SMTH的成员
     */
    private void removeFactionMember(long factionId, List<Long> userIdList) {
        List<TornUserDO> allFactionUserList = userDao.list();
        for (TornUserDO user : allFactionUserList) {
            if (!userIdList.contains(user.getId())) {
                userDao.lambdaUpdate()
                        .set(TornUserDO::getFactionId, 0L)
                        .eq(TornUserDO::getId, user.getId())
                        .eq(TornUserDO::getFactionId, factionId)
                        .update();
                ocUserDao.updateUserFaction(factionId, user.getId());
            }
        }
    }
}