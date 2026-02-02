package pn.torn.goldeneye.napcat.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.base.BaseMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;

import java.util.List;

/**
 * 获取指令手册
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.08.15
 */
@Component
@RequiredArgsConstructor
public class DocStrategyImpl extends SmthMsgStrategy {
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
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        List<BaseGroupMsgStrategy> groupStrategyList = applicationContext
                .getBeansOfType(BaseGroupMsgStrategy.class)
                .values().stream()
                .filter(s -> !(s instanceof DocStrategyImpl) &&
                        !(s instanceof ManageDocStrategyImpl) && !s.isNeedSa())
                .filter(s -> s.getCustomGroupId().isEmpty() || s.getCustomGroupId().contains(groupId))
                .filter(strategy -> strategy.getRoleType() == null)
                .toList();

        StringBuilder helpText = new StringBuilder("可用指令列表，以g#开头，括号内为可选参数\n");
        appendCommandDesc(applicationContext.getBean(BindKeyStrategyImpl.class), helpText);

        groupStrategyList.forEach(strategy -> appendCommandDesc(strategy, helpText));
        return buildTextMsg(helpText.toString());
    }

    private void appendCommandDesc(BaseMsgStrategy strategy, StringBuilder helpText) {
        helpText.append(strategy.getCommand())
                .append(" - ")
                .append(strategy.getCommandDescription())
                .append("\n");
    }
}