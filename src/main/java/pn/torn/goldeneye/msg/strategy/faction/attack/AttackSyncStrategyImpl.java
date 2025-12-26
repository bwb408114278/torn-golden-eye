package pn.torn.goldeneye.msg.strategy.faction.attack;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.service.faction.attack.TornFactionAttackService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步战斗数据策略实现类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.25
 */
@Component
@RequiredArgsConstructor
public class AttackSyncStrategyImpl extends BaseGroupMsgStrategy {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornFactionAttackService attackService;
    private final TornSettingFactionManager settingFactionManager;

    @Override
    public boolean isNeedSa() {
        return true;
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return null;
    }

    @Override
    public String getCommand() {
        return BotCommands.FACTION_ATTACK;
    }

    @Override
    public String getCommandDescription() {
        return "强制刷新帮派战斗记录，慎用（格式不告诉你）";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        String[] msgArray = msg.split("#");

        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(Long.parseLong(msgArray[0]));
        LocalDateTime from = DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[1]));
        LocalDateTime to = DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[2]));
        virtualThreadExecutor.execute(() -> attackService.spiderAttackData(faction, from, to));
        return super.buildTextMsg("同步攻击记录中, 请注意查看日志和数据");
    }
}