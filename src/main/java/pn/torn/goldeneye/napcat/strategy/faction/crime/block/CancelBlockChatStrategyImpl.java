package pn.torn.goldeneye.napcat.strategy.faction.crime.block;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;

/**
 * 屏蔽聊天取消实现类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.18
 */
@Component
@RequiredArgsConstructor
public class CancelBlockChatStrategyImpl extends BaseBlockChatStrategyImpl {
    @Override
    public String getCommand() {
        return BotCommands.CANCEL_BLOCK_CHAT;
    }

    @Override
    public String getCommandDescription() {
        return "金眼开始发送消息";
    }

    @Override
    protected boolean isBlock() {
        return false;
    }

    @Override
    protected String buildSuccessMsg() {
        return "龙王模式已开启";
    }
}