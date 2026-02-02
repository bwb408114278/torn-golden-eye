package pn.torn.goldeneye.napcat.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.base.BasePrivateMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;

import java.util.Collection;
import java.util.List;

/**
 * 获取私聊指令手册
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.02
 */
@Component
@RequiredArgsConstructor
public class PrivateDocStrategyImpl extends SmthMsgStrategy {
    private final ApplicationContext applicationContext;
    private final ProjectProperty projectProperty;

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
        Collection<BasePrivateMsgStrategy> privateStrategyList = applicationContext
                .getBeansOfType(BasePrivateMsgStrategy.class)
                .values();

        StringBuilder helpText = new StringBuilder("可用指令列表，以g#开头，括号内为可选参数\n");
        helpText.append("如需订阅VIP功能, 发送2Xan到3312605, 并备注" + TornConstants.REMARK_SUBSCRIBE)
                .append("\n然后申请群").append(projectProperty.getVipGroupId())
                .append(", 金眼会自动通过入群申请(内测中, 当前为优惠价格, 支持一次订阅多月)\n");

        privateStrategyList.forEach(strategy -> appendCommandDesc(strategy, helpText));
        return buildTextMsg(helpText.toString());
    }

    private void appendCommandDesc(BaseMsgStrategy strategy, StringBuilder helpText) {
        helpText.append(strategy.getCommand())
                .append(" - ")
                .append(strategy.getCommandDescription())
                .append("\n");
    }
}