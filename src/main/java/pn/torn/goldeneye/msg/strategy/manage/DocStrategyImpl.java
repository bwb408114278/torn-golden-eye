package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.GroupRecSender;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;

import java.util.List;

/**
 * 获取指令手册
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.15
 */
@Component
@RequiredArgsConstructor
public class DocStrategyImpl extends PnMsgStrategy {
    private final ApplicationContext applicationContext;

    @Override
    public String getCommand() {
        return BotCommands.DOC;
    }

    @Override
    public String getCommandDescription() {
        return "列出所有可用指令";
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(long groupId, GroupRecSender sender, String msg) {
        List<BaseMsgStrategy> strategies = applicationContext.getBeansOfType(BaseMsgStrategy.class)
                .values().stream()
                .filter(strategy -> !(strategy instanceof DocStrategyImpl))
                .toList();

        StringBuilder helpText = new StringBuilder("可用指令列表，以g#开头，括号内为可选参数\n");
        strategies.stream()
                .filter(strategy -> ArrayUtils.contains(strategy.getGroupId(), groupId))
                .forEach(strategy -> helpText.append(strategy.getCommand())
                        .append(" - ")
                        .append(strategy.getCommandDescription())
                        .append("\n"));

        return buildTextMsg(helpText.toString());
    }
}