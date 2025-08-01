package pn.torn.goldeneye.msg.strategy;

import pn.torn.goldeneye.constants.bot.BotConstants;

/**
 * Pn群消息策略
 *
 * @author Bai
 * @version 1.0
 * @since 2025.07.24
 */
public abstract class PnMsgStrategy extends BaseMsgStrategy {
    @Override
    public long getGroupId() {
        return BotConstants.PN_GROUP_ID;
    }
}