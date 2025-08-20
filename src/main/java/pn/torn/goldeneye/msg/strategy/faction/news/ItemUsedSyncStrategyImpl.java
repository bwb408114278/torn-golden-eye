package pn.torn.goldeneye.msg.strategy.faction.news;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.GroupRecSender;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
import pn.torn.goldeneye.torn.service.faction.armory.ItemUsedService;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class ItemUsedSyncStrategyImpl extends BaseMsgStrategy {
    private final ItemUsedService itemUsedService;

    @Override
    public String getCommand() {
        return BotCommands.ITEM_USED;
    }

    @Override
    public String getCommandDescription() {
        return "强制刷新帮派物品使用记录，慎用（格式不告诉你）";
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(long groupId, GroupRecSender sender, String msg) {
        String[] msgArray = msg.split("#");
        if (msgArray.length < 2 || !NumberUtils.isInt(msgArray[0]) || !NumberUtils.isInt(msgArray[1])) {
            return super.sendErrorFormatMsg();
        }

        LocalDateTime from = DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[0]));
        LocalDateTime to = DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[1]));
        if (from.isAfter(to)) {
            return super.sendErrorFormatMsg();
        }

        itemUsedService.spiderItemUseData(from, to);

        return super.buildTextMsg("同步" +
                DateTimeUtils.convertToString(from) +
                "到" +
                DateTimeUtils.convertToString(DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[1]))) +
                "的帮派物品使用记录完成");
    }
}