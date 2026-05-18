package pn.torn.goldeneye.torn.manager.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.napcat.receive.member.GroupMemberDataRec;
import pn.torn.goldeneye.napcat.receive.member.GroupMemberRec;
import pn.torn.goldeneye.napcat.send.GroupMemberReqParam;

import java.util.List;

/**
 * QQ用户公共逻辑层
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.13
 */
@Component
@RequiredArgsConstructor
public class TornQqUserManager {
    private final Bot bot;

    /**
     * 获取群成员QQ号列表
     */
    public List<Long> getGroupQqIdList(long groupId) {
        List<GroupMemberDataRec> memberList = getGroupMemberList(groupId);
        return memberList.stream().map(GroupMemberDataRec::getUserId).toList();
    }

    /**
     * 获取群成员列表
     */
    public List<GroupMemberDataRec> getGroupMemberList(long groupId) {
        ResponseEntity<GroupMemberRec> resp = bot.sendRequest(new GroupMemberReqParam(groupId), GroupMemberRec.class);
        return resp.getBody() == null ? List.of() : resp.getBody().getData();
    }
}