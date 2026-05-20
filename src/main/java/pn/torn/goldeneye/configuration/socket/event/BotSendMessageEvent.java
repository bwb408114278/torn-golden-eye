package pn.torn.goldeneye.configuration.socket.event;

import pn.torn.goldeneye.base.bot.BotSocketReqParam;

public class BotSendMessageEvent {

    private final BotSocketReqParam param;

    public BotSendMessageEvent(BotSocketReqParam param) {
        this.param = param;
    }

    public BotSocketReqParam getParam() {
        return param;
    }
}