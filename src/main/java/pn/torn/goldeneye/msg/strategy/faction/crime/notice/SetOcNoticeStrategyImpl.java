package pn.torn.goldeneye.msg.strategy.faction.crime.notice;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;

/**
 * OC通知设置实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class SetOcNoticeStrategyImpl extends BaseOcNoticeStrategyImpl {
    @Override
    public String getCommand() {
        return BotCommands.OC_NOTICE;
    }

    @Override
    public String getCommandDescription() {
        return "设置轮转提醒，命令格式g#" + BotCommands.OC_NOTICE + "#OC等级";
    }

    @Override
    protected boolean hasNotice() {
        return true;
    }

    @Override
    protected String buildSuccessMsg(long userId, int rank) {
        return userId + "已设置" + rank + "级轮转提醒";
    }
}