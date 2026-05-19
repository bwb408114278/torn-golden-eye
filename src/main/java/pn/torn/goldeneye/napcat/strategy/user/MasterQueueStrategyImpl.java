package pn.torn.goldeneye.napcat.strategy.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.torn.MasterQueueManager;
import pn.torn.goldeneye.torn.model.user.master.MasterQueueAroundVO;
import pn.torn.goldeneye.torn.model.user.master.MasterQueueVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 师父排队策略实现类
 *
 * @author Bai
 * @version 1.1.2
 * @since 2026.05.18
 */
@Component
@RequiredArgsConstructor
public class MasterQueueStrategyImpl extends SmthMsgStrategy {
    private final MasterQueueManager masterQueueManager;

    @Override
    public String getCommand() {
        return BotCommands.MASTER_QUEUE;
    }

    @Override
    public String getCommandDescription() {
        return "查询自己当前师父排队的顺序";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornUserDO user = super.getTornUser(sender, "");
        MasterQueueAroundVO queue = getMasterQueueAround(user.getId());
        if (queue == null) {
            return super.buildTextMsg("没有查询到师父排队信息, 请确认导员已将你加入排队表");
        }

        StringBuilder builder = new StringBuilder();
        builder.append(user.getNickname()).append("的当前顺序为: ").append(queue.getRank());
        for (MasterQueueVO aroundUser : queue.getAroundUsers()) {
            builder.append(user.getId().equals(aroundUser.getUserId()) ? "\n★ " : "\n    ")
                    .append(aroundUser.getRank());
            builder.append(" ").append(aroundUser.getNickname());
            builder.append(" [").append(aroundUser.getUserId()).append("]");
        }

        return super.buildTextMsg(builder.toString());
    }

    /**
     * 根据用户ID获取该用户当前排名及附近最多4人的排队信息
     */
    public MasterQueueAroundVO getMasterQueueAround(long userId) {
        List<MasterQueueVO> queueList = masterQueueManager.getMasterQueue();
        if (CollectionUtils.isEmpty(queueList)) {
            return null;
        }

        int index = -1;
        for (int i = 0; i < queueList.size(); i++) {
            if (queueList.get(i).getUserId() == userId) {
                index = i;
                break;
            }
        }

        if (index == -1) {
            return null;
        }

        int total = queueList.size();
        int start = Math.max(0, index - 2);
        int end = Math.min(total - 1, index + 2);
        // 不足5个时，尽量向另一侧补齐
        while (end - start + 1 < Math.min(5, total)) {
            if (start > 0) {
                start--;
            } else if (end < total - 1) {
                end++;
            } else {
                break;
            }
        }

        List<MasterQueueVO> aroundList = new ArrayList<>(queueList.subList(start, end + 1));
        MasterQueueVO currentUser = queueList.get(index);
        return new MasterQueueAroundVO(currentUser.getRank(), currentUser, aroundList);
    }
}