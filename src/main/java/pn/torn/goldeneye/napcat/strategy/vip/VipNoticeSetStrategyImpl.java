package pn.torn.goldeneye.napcat.strategy.vip;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.bot.enums.VipNoticeTypeEnum;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeConfigDO;

import java.util.List;

/**
 * 设置VIP提醒
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.14
 */
@Component
@RequiredArgsConstructor
public class VipNoticeSetStrategyImpl extends BaseVipNoticeConfigStrategyImpl {
    private final TornApiKeyConfig apiKeyConfig;

    @Override
    public String getCommand() {
        return BotCommands.VIP_NOTICE_SET;
    }

    @Override
    public String getCommandDescription() {
        return "设置群里提醒的的类型(药、EN等)";
    }

    @Override
    protected List<? extends QqMsgParam<?>> handle(TornUserDO user, String msg) {
        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(user.getId());
        if (key == null) {
            return super.buildTextMsg("个人提醒需要先绑定Torn Api");
        }
        apiKeyConfig.returnKey(key);
        return super.handle(user, msg);
    }

    @Override
    protected String getSubCommandDesc() {
        return "请输入需要设置提醒的类型";
    }

    @Override
    protected boolean needChange(VipNoticeConfigDO config, VipNoticeTypeEnum type) {
        return !config.isEnabled(type);
    }

    @Override
    protected int handleChangeType(VipNoticeTypeEnum type) {
        return type.getBit();
    }
}