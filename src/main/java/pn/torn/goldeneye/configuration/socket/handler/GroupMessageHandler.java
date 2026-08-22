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
import pn.torn.goldeneye.napcat.strategy.base.BaseMsgStrategy;
import pn.torn.goldeneye.napcat.strategy.manage.DocStrategyImpl;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.TornFactionBO;

import java.util.List;

/**
 * 群聊消息处理器
 *
 * @author Bai
 * @version 1.4.0
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
     * 处理群消息（兼容无 at 标记调用）。
     *
     * @param msg      入站消息
     * @param msgArray 命令数组
     * @param faction  群对应帮派
     */
    public void handle(QqRecMsg msg, String[] msgArray, TornFactionBO faction) {
        handle(msg, msgArray, "", faction);
    }

    /**
     * 处理群消息。
     *
     * @param msg      入站消息
     * @param msgArray 命令数组
     * @param atMarker 内部 at 目标标记；无 at 时为空字符串
     * @param faction  群对应帮派
     */
    public void handle(QqRecMsg msg, String[] msgArray, String atMarker, TornFactionBO faction) {
        if (!StringUtils.hasText(msgArray[1])) {
            replyDocMessage(msg, msgArray, atMarker, faction);
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
            List<? extends QqMsgParam<?>> paramList = buildReplyMsg(msg, msgArray, atMarker, strategy);
            paramList.forEach(builder::addMsg);
        }
        TornFactionBO latestFaction = factionManager.getByGroup(msg.getGroupId());
        botReplyService.replyGroup(latestFaction, builder.build());
    }

    /**
     * 回复手册消息
     */
    private void replyDocMessage(QqRecMsg msg, String[] msgArray, String atMarker, TornFactionBO faction) {
        GroupMsgSocketBuilder builder = new GroupMsgSocketBuilder().setGroupId(msg.getGroupId());
        List<? extends QqMsgParam<?>> paramList = buildReplyMsg(msg, msgArray, atMarker, docStrategy);
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
                                                        String atMarker,
                                                        BaseGroupMsgStrategy strategy) {
        try {
            String param = resolveParam(msgArray, atMarker, strategy);
            return strategy.handle(msg.getGroupId(), msg.getSender(), param);
        } catch (BizException e) {
            return strategy.buildTextMsg(e.getMsg());
        }
    }

    /**
     * 组装策略参数：无 at 时保持纯文本参数；有 at 时仅用户查询策略接收内部 at 标记，
     * 不支持 at 的策略拒绝执行并返回稳定错误，不静默丢弃 at。
     */
    private String resolveParam(String[] msgArray, String atMarker, BaseGroupMsgStrategy strategy) {
        String plainParam = msgArray.length > 2 ? msgArray[2] : "";
        if (!StringUtils.hasText(atMarker)) {
            return plainParam;
        }
        if (!strategy.supportsAtUserTarget()) {
            throw new BizException(BaseMsgStrategy.AT_UNSUPPORTED_MSG);
        }
        return plainParam + atMarker;
    }
}