package pn.torn.goldeneye.torn.manager.faction.oc;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.msg.receive.member.GroupMemberDataRec;
import pn.torn.goldeneye.msg.receive.member.GroupMemberRec;
import pn.torn.goldeneye.msg.send.GroupMemberReqParam;
import pn.torn.goldeneye.msg.send.param.AtGroupMsg;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OC消息公共逻辑
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcMsgManager {
    private final Bot bot;
    private final TornFactionOcUserManager ocUserManager;
    private final TornFactionOcSlotDAO slotDao;
    private final TornUserDAO userDao;

    /**
     * 构建岗位详细消息
     *
     * @return 岗位消息消息
     */
    public List<GroupMsgParam<?>> buildSlotMsg(TornFactionOcDO oc) {
        List<TornFactionOcSlotDO> slotList = slotDao.lambdaQuery().eq(TornFactionOcSlotDO::getOcId, oc.getId()).list();
        ResponseEntity<GroupMemberRec> memberList = bot.sendRequest(
                new GroupMemberReqParam(BotConstants.PN_GROUP_ID), GroupMemberRec.class);

        List<GroupMsgParam<?>> resultList = new ArrayList<>();
        for (TornFactionOcSlotDO slot : slotList) {
            resultList.add(buildOcMsg(slot.getUserId(), memberList));
        }

        Set<Long> freeUserIdSet = ocUserManager.findRotationUser(oc.getRank());
        for (Long userId : freeUserIdSet) {
            resultList.add(buildOcMsg(userId, memberList));
        }

        return resultList;
    }

    /**
     * 构建OC提醒消息
     *
     * @param userId 用户ID
     */
    private GroupMsgParam<?> buildOcMsg(long userId, ResponseEntity<GroupMemberRec> memberList) {
        String card = "[" + userId + "]";
        GroupMemberDataRec member = memberList.getBody().getData().stream().filter(m ->
                m.getCard().contains(card)).findAny().orElse(null);
        if (member == null) {
            TornUserDO user = userDao.getById(userId);
            return new TextGroupMsg((user == null ?
                    userId :
                    user.getNickname()) + card + " ");
        } else {
            return new AtGroupMsg(member.getUserId());
        }
    }
}