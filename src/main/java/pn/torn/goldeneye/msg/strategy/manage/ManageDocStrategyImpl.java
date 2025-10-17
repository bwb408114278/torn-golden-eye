package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.msg.strategy.base.BaseMsgStrategy;

import java.util.List;

/**
 * 获取管理指令手册
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.15
 */
@Component
@RequiredArgsConstructor
public class ManageDocStrategyImpl extends BaseGroupMsgStrategy {
    private final ApplicationContext applicationContext;
    private final ProjectProperty projectProperty;

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
        boolean isSa = projectProperty.getAdminId().contains(sender.getUserId());

        List<BaseGroupMsgStrategy> groupStrategyList = applicationContext
                .getBeansOfType(BaseGroupMsgStrategy.class)
                .values().stream()
                .filter(s -> !(s instanceof ManageDocStrategyImpl))
                .filter(s -> s.getCustomGroupId() == 0L || groupId == s.getCustomGroupId())
                .filter(s -> !s.isNeedSa() || isSa)
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