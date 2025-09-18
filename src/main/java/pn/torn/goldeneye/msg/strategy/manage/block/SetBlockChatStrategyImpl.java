package pn.torn.goldeneye.msg.strategy.manage.block;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;

/**
 * 屏蔽聊天设置实现类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.18
 */
@Component
@RequiredArgsConstructor
public class SetBlockChatStrategyImpl extends BaseBlockChatStrategyImpl {
    @Override
    public String getCommand() {
        return BotCommands.BLOCK_CHAT;
    }

    @Override
    public String getCommandDescription() {
        return "金眼停止发送消息";
    }

    @Override
    protected boolean isBlock() {
        return true;
    }

    @Override
    protected String buildSuccessMsg() {
        return "检测到三体入侵，通讯已切断";
    }
}