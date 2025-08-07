package pn.torn.goldeneye.msg.strategy.faction.member;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 匹配帮派成员策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.04
 */
@Component
@RequiredArgsConstructor
public class MatchMemberStrategyImpl extends BaseMsgStrategy {
    private final TornApi tornApi;
    private final TornUserDAO userDao;

    @Override
    public String getCommand() {
        return BotCommands.MATCH_MEMBER;
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        TornFactionMemberDTO param = new TornFactionMemberDTO(TornConstants.FACTION_PN_ID);
        TornFactionMemberListVO memberList = tornApi.sendRequest(param, TornFactionMemberListVO.class);

        List<Long> userIdList = memberList.getMembers().stream().map(TornFactionMemberVO::getId).toList();
        List<TornUserDO> userList = userDao.lambdaQuery().in(TornUserDO::getId, userIdList).list();

        List<TornUserDO> newUserList = new ArrayList<>();
        for (TornFactionMemberVO member : memberList.getMembers()) {
            if (userList.stream().noneMatch(u -> u.getId().equals(member.getId()))) {
                newUserList.add(member.convert2DO(TornConstants.FACTION_PN_ID));
            }
        }

        if (!CollectionUtils.isEmpty(newUserList)) {
            userDao.saveBatch(newUserList);
        }

        return super.buildTextMsg("匹配" + TornConstants.FACTION_PN_ID + "成员成功");
    }
}