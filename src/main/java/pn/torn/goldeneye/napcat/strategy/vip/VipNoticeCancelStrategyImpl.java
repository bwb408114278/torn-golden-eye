package pn.torn.goldeneye.napcat.strategy.vip;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.bot.enums.VipNoticeTypeEnum;
import pn.torn.goldeneye.repository.model.vip.VipNoticeConfigDO;

/**
 * 取消VIP提醒
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.14
 */
@Component
@RequiredArgsConstructor
public class VipNoticeCancelStrategyImpl extends BaseVipNoticeConfigStrategyImpl {
    @Override
    public String getCommand() {
        return BotCommands.VIP_NOTICE_CANCEL;
    }

    @Override
    public String getCommandDescription() {
        return "取消群里提醒的的类型";
    }

    @Override
    protected String getSubCommandDesc() {
        return "请输入需要取消提醒的类型";
    }

    @Override
    protected boolean needChange(VipNoticeConfigDO config, VipNoticeTypeEnum type) {
        return !config.isEnabled(type);
    }

    @Override
    protected int handleChangeType(VipNoticeTypeEnum type) {
        return type.getBit() * -1;
    }
}