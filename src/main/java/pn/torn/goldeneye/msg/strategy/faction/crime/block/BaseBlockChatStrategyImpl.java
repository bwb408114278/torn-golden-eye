package pn.torn.goldeneye.msg.strategy.faction.crime.block;

import jakarta.annotation.Resource;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.msg.strategy.base.PnManageMsgStrategy;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;

import java.util.List;

/**
 * 屏蔽聊天设置抽象实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.09.18
 */
public abstract class BaseBlockChatStrategyImpl extends PnManageMsgStrategy {
    @Resource
    private SysSettingManager settingManager;
    @Resource
    private Bot bot;

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (isBlock() == settingManager.getIsBlockChat()) {
            return super.buildTextMsg("已经设置过了, 无事发生");
        }
        // 发送两次, 禁言true在禁言前发送, 禁言后在禁言后发送, 否则会被拦截
        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(groupId)
                .addMsg(new TextQqMsg(buildSuccessMsg()))
                .build();
        bot.sendRequest(param, String.class);
        settingManager.updateSetting(SettingConstants.KEY_BLOCK_CHAT, String.valueOf(isBlock()));
        return super.buildTextMsg(buildSuccessMsg());
    }

    /**
     * 是否屏蔽
     *
     * @return true为是
     */
    protected abstract boolean isBlock();

    /**
     * 构建设置成功消息
     *
     * @return 发送到群里的消息
     */
    protected abstract String buildSuccessMsg();
}