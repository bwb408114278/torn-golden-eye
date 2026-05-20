package pn.torn.goldeneye.configuration.socket.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.configuration.socket.service.BotReplyService;
import pn.torn.goldeneye.configuration.socket.service.GroupPermissionService;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsg;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgSocketBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.manage.DocStrategyImpl;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.TornFactionBO;

import java.util.List;

/**
 * 群聊消息处理器
 *
 * @author Bai
 * @version 1.1.3
 * @since 2026.05.20
 */
@Component
@RequiredArgsConstructor
public class GroupMessageHandler {
    private final List<BaseGroupMsgStrategy> groupMsgStrategyList;
    private final DocStrategyImpl docStrategy;
    private final TornSettingFactionManager factionManager;
    private final GroupPermissionService groupPermissionService;
    private final BotReplyService botReplyService;

    /**
     * 处理群消息
     */
    public void handle(QqRecMsg msg, String[] msgArray, TornFactionBO faction) {
        if (!StringUtils.hasText(msgArray[1])) {
            replyDocMessage(msg, msgArray, faction);
            return;
        }
        BaseGroupMsgStrategy strategy = findStrategy(msgArray[1]);
        if (strategy == null) {
            return;
        }
        if (isNotAllowedGroup(strategy, msg.getGroupId())) {
            return;
        }
        GroupMsgSocketBuilder builder = new GroupMsgSocketBuilder().setGroupId(msg.getGroupId());
        if (groupPermissionService.invalidAdmin(msg.getUserId(), strategy, faction)) {
            builder.addMsg(new TextQqMsg("没有对应的权限"));
        } else {
            List<? extends QqMsgParam<?>> paramList = buildReplyMsg(msg, msgArray, strategy);
            paramList.forEach(builder::addMsg);
        }
        TornFactionBO latestFaction = factionManager.getByGroup(msg.getGroupId());
        botReplyService.replyGroup(latestFaction, builder.build());
    }

    /**
     * 回复手册消息
     */
    private void replyDocMessage(QqRecMsg msg, String[] msgArray, TornFactionBO faction) {
        GroupMsgSocketBuilder builder = new GroupMsgSocketBuilder().setGroupId(msg.getGroupId());
        List<? extends QqMsgParam<?>> paramList = buildReplyMsg(msg, msgArray, docStrategy);
        paramList.forEach(builder::addMsg);
        botReplyService.replyGroup(faction, builder.build());
    }

    /**
     * 寻找消息执行策略
     */
    private BaseGroupMsgStrategy findStrategy(String command) {
        for (BaseGroupMsgStrategy strategy : groupMsgStrategyList) {
            if (strategy.getCommand().equalsIgnoreCase(command)) {
                return strategy;
            }
        }
        return null;
    }

    /**
     * 是否未开放功能的群聊
     *
     * @return true为未开放（禁用）
     */
    private boolean isNotAllowedGroup(BaseGroupMsgStrategy strategy, Long groupId) {
        return !strategy.getCustomGroupId().isEmpty()
                && !strategy.getCustomGroupId().contains(groupId);
    }

    /**
     * 构建群消息回复
     */
    private List<? extends QqMsgParam<?>> buildReplyMsg(QqRecMsg msg,
                                                        String[] msgArray,
                                                        BaseGroupMsgStrategy strategy) {
        try {
            return strategy.handle(msg.getGroupId(), msg.getSender(), msgArray.length > 2 ? msgArray[2] : "");
        } catch (BizException e) {
            return strategy.buildTextMsg(e.getMsg());
        }
    }
}