package pn.torn.goldeneye.napcat.strategy.faction.attack;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.PnManageMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RW真赛结束策略实现类
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.04.22
 */
@Component
@RequiredArgsConstructor
public class FactionRwEndStrategyImpl extends PnManageMsgStrategy {
    private final DynamicTaskService taskService;
    private final TornFactionRwDAO rwDao;
    private final TornSettingFactionManager settingFactionManager;

    @Override
    public String getCommand() {
        return BotCommands.RW_END;
    }

    @Override
    public String getCommandDescription() {
        return "手动结束RW，停止消息提醒和数据抓取";
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.WAR_COMMANDER;
    }

    @Override
    public List<Long> getCustomGroupId() {
        return List.of(projectProperty.getGroupId(), BotConstants.GROUP_CCRC_ID, BotConstants.GROUP_SH_ID);
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornUserDO user = super.getTornUser(sender, "");
        TornFactionRwDO rw = rwDao.lambdaQuery()
                .eq(TornFactionRwDO::getFactionId, user.getFactionId())
                .isNull(TornFactionRwDO::getEndTime)
                .one();
        if (rw == null) {
            return super.buildTextMsg("未查询到已登记的真赛");
        }

        LocalDateTime now = LocalDateTime.now();
        rwDao.lambdaUpdate()
                .set(TornFactionRwDO::getEndTime, now)
                .eq(TornFactionRwDO::getId, rw.getId())
                .update();

        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(user.getFactionId());
        taskService.cancelTask(faction.getFactionShortName() + "-rw-data-reload");

        return super.buildTextMsg("RW已手动结束, 诸君辛苦了!");
    }
}