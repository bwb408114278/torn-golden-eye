package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.msg.strategy.base.BaseMsgStrategy;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * 获取管理指令手册
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.15
 */
@Component
@RequiredArgsConstructor
public class ManageDocStrategyImpl extends BaseGroupMsgStrategy {
    private final ApplicationContext applicationContext;
    private final TornSettingFactionManager factionManager;
    private final ProjectProperty projectProperty;

    @Override
    public String getCommand() {
        return BotCommands.MANAGE_DOC;
    }

    @Override
    public String getCommandDescription() {
        return "列出所有可用管理员指令";
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return null;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        boolean isSa = projectProperty.getAdminId().contains(sender.getUserId());
        TornFactionRoleTypeEnum role;
        if (!isSa) {
            TornUserDO user = getTornUser(sender, "");
            role = checkUserRoleType(sender.getUserId(), user.getFactionId());
        } else {
            role = null;
        }

        if (!isSa && role == null) {
            return super.buildTextMsg("没有对应的权限");
        }

        List<BaseGroupMsgStrategy> groupStrategyList = applicationContext
                .getBeansOfType(BaseGroupMsgStrategy.class)
                .values().stream()
                .filter(s -> !(s instanceof DocStrategyImpl) && !(s instanceof ManageDocStrategyImpl))
                .filter(s -> s.getCustomGroupId().isEmpty() || s.getCustomGroupId().contains(groupId))
                .filter(s -> checkStrategyRole(s, isSa, role))
                .toList();

        StringBuilder helpText = new StringBuilder("可用指令列表，以g#开头，括号内为可选参数\n");
        groupStrategyList.forEach(strategy -> appendCommandDesc(strategy, helpText));

        return buildTextMsg(helpText.toString());
    }

    /**
     * 检测用户是何种角色
     */
    private TornFactionRoleTypeEnum checkUserRoleType(long userId, long factionId) {
        TornSettingFactionDO faction = factionManager.getIdMap().get(factionId);

        List<Long> leaderList = NumberUtils.splitToLongList(faction.getGroupAdminIds());
        if (leaderList.contains(userId)) {
            return TornFactionRoleTypeEnum.LEADER;
        }

        List<Long> ocCommanderList = NumberUtils.splitToLongList(faction.getOcCommanderIds());
        if (ocCommanderList.contains(userId)) {
            return TornFactionRoleTypeEnum.OC_COMMANDER;
        }

        return null;
    }

    /**
     * 检查消息策略需要的角色类型
     */
    private boolean checkStrategyRole(BaseGroupMsgStrategy strategy, boolean isSa, TornFactionRoleTypeEnum role) {
        if (strategy.isNeedSa() && isSa) {
            return true;
        } else if (strategy.getRoleType() == null) {
            return false;
        }

        if (isSa || TornFactionRoleTypeEnum.LEADER.equals(role)) {
            return true;
        }

        return strategy.getRoleType().equals(role);
    }

    private void appendCommandDesc(BaseMsgStrategy strategy, StringBuilder helpText) {
        helpText.append(strategy.getCommand())
                .append(" - ")
                .append(strategy.getCommandDescription())
                .append("\n");
    }
}