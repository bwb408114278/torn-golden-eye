package pn.torn.goldeneye.msg.strategy.faction.crime.block;

import jakarta.annotation.Resource;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;

import java.util.List;

/**
 * 屏蔽聊天设置抽象实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.09.18
 */
public abstract class BaseBlockChatStrategyImpl extends BaseGroupMsgStrategy {
    @Resource
    private TornSettingFactionManager factionManager;
    @Resource
    private TornSettingFactionDAO factionDao;
    @Resource
    private Bot bot;

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornSettingFactionDO faction = factionManager.getGroupIdMap().get(groupId);

        if (faction == null || isBlock() == faction.getMsgBlock()) {
            return super.buildTextMsg("已经设置过了, 无事发生");
        }
        // 发送两次, 禁言true在禁言前发送, 禁言后在禁言后发送, 否则会被拦截
        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(groupId)
                .addMsg(new TextQqMsg(buildSuccessMsg()))
                .build();
        bot.sendRequest(param, String.class);
        factionDao.lambdaUpdate()
                .set(TornSettingFactionDO::getMsgBlock, isBlock())
                .eq(TornSettingFactionDO::getId, faction.getId())
                .update();
        factionManager.refreshCache();
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