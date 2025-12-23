package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.service.faction.attack.TornFactionAttackService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取当前任务策略实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class TestStrategyImpl extends BaseGroupMsgStrategy {
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
        return "测试";
    }

    @Override
    public String getCommandDescription() {
        return "测试";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(TornConstants.FACTION_PN_ID);
        LocalDateTime from = LocalDateTime.of(2025, 9, 4, 21, 0, 0);
        LocalDateTime to = LocalDateTime.of(2025, 9, 4, 21, 5, 0);
        attackService.spiderAttackData(faction, from, to);
        return super.buildTextMsg("同步攻击记录中, 请注意查看日志和数据");
    }
}