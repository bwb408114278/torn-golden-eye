package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.BaseGroupMsgStrategy;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;

import java.util.List;

/**
 * 获取管理指令手册
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.15
 */
@Component
@RequiredArgsConstructor
public class ManageDocStrategyImpl extends BaseGroupMsgStrategy {
    private final ApplicationContext applicationContext;

    @Override
    public String getCommand() {
        return BotCommands.MANAGE_DOC;
    }

    @Override
    public String getCommandDescription() {
        return "列出所有可用管理员指令";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        List<BaseGroupMsgStrategy> groupStrategyList = applicationContext
                .getBeansOfType(BaseGroupMsgStrategy.class)
                .values().stream()
                .filter(strategy -> !(strategy instanceof ManageDocStrategyImpl))
                .toList();

        StringBuilder helpText = new StringBuilder("可用指令列表，以g#开头，括号内为可选参数\n");
        groupStrategyList.stream()
                .filter(BaseGroupMsgStrategy::isNeedAdmin)
                .forEach(strategy -> appendCommandDesc(strategy, helpText));

        return buildTextMsg(helpText.toString());
    }

    private void appendCommandDesc(BaseMsgStrategy strategy, StringBuilder helpText) {
        helpText.append(strategy.getCommand())
                .append(" - ")
                .append(strategy.getCommandDescription())
                .append("\n");
    }
}