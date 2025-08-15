package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcUserManager;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class OcMemberStrategyImpl extends PnMsgStrategy {
    private final TornFactionOcUserManager userManager;
    private final TornUserDAO userDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_FREE;
    }

    @Override
    public String getCommandDescription() {
        return "获取没加OC的人，格式g#" + BotCommands.OC_FREE + "#OC级别#岗位";
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(long groupId, String msg) {
        String[] msgArray = msg.split("#");
        if (msgArray.length != 2 || !NumberUtils.isInt(msgArray[0])) {
            return super.sendErrorFormatMsg();
        }

        List<TornFactionOcUserDO> userList = userManager.findFreeUser(Integer.parseInt(msgArray[0]), msgArray[1]);
        if (CollectionUtils.isEmpty(userList)) {
            return super.buildTextMsg("暂时没有可以加入OC的成员");
        }

        Set<Long> userIdList = userList.stream().map(TornFactionOcUserDO::getUserId).collect(Collectors.toSet());
        List<TornUserDO> userDataList = userDao.lambdaQuery().in(TornUserDO::getId, userIdList).list();

        StringBuilder builder = new StringBuilder();
        builder.append("空闲人员如下: ");
        for (TornFactionOcUserDO user : userList) {
            TornUserDO userData = userDataList.stream().filter(u ->
                    u.getId().equals(user.getUserId())).findAny().orElse(null);
            if (userData == null) {
                continue;
            }

            builder.append("\n")
                    .append(userData.getNickname())
                    .append("[").append(userData.getId()).append("] ")
                    .append(user.getPosition())
                    .append(" 成功率: ").append(user.getPassRate());
        }

        return super.buildTextMsg(builder.toString());
    }
}