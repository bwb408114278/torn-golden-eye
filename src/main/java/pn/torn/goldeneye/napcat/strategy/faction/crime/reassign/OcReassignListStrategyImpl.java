package pn.torn.goldeneye.napcat.strategy.faction.crime.reassign;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.service.faction.oc.reassign.OcReassignConfigService;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * OC大锅饭名单查询策略实现类
 *
 * <p>无角色门槛；带帮派ID前缀仅超管可用，默认查询发送者所属帮派。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@Component
@RequiredArgsConstructor
public class OcReassignListStrategyImpl extends BaseGroupMsgStrategy {
    private final OcReassignConfigService reassignConfigService;
    private final TornSettingFactionManager settingFactionManager;
    private final ProjectProperty projectProperty;

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return null;
    }

    @Override
    public String getCommand() {
        return BotCommands.OC_REASSIGN_LIST;
    }

    @Override
    public String getCommandDescription() {
        return "查询帮派大锅饭名单, 例g#" + BotCommands.OC_REASSIGN_LIST + "(#帮派ID)";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        String[] msgArray = msg.split("#");
        long factionId;
        if (StringUtils.hasText(msg) && msgArray.length == 1) {
            if (!NumberUtils.isLong(msgArray[0])) {
                return super.buildTextMsg("参数有误，正确格式：g#" + BotCommands.OC_REASSIGN_LIST + "(#帮派ID)");
            }
            if (!projectProperty.getAdminId().contains(sender.getUserId())) {
                return super.buildTextMsg("失败: 仅超管可指定帮派ID");
            }
            factionId = Long.parseLong(msgArray[0]);
        } else if (msgArray.length > 1) {
            return super.buildTextMsg("参数有误，正确格式：g#" + BotCommands.OC_REASSIGN_LIST + "(#帮派ID)");
        } else {
            TornUserDO user = super.getTornUser(sender, "");
            factionId = user.getFactionId() == null ? 0L : user.getFactionId();
        }

        OcReassignConfigService.ListResult result = reassignConfigService.listFaction(factionId);
        if (!result.success()) {
            return super.buildTextMsg("该帮派未开启大锅饭");
        }
        return super.buildTextMsg(buildListMsg(result));
    }

    /**
     * 拼装名单回执：帮派、模式、逐行OC名称[级别]生效时间（或"始终"），缺规划行的OC追加标记。
     *
     * @param result 名单查询结果
     * @return 名单回执文本
     */
    private String buildListMsg(OcReassignConfigService.ListResult result) {
        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(result.factionId());
        String factionLabel = faction == null ? String.valueOf(result.factionId()) : faction.getFactionShortName();
        List<String> lines = new ArrayList<>();
        lines.add("大锅饭名单: " + factionLabel + "(" + result.factionId() + ") 模式="
                + result.incomeMode().getLabel());
        for (OcReassignConfigService.ScopeRow row : result.rows()) {
            String effectiveFrom = row.effectiveFrom() == null ?
                    "始终" : DateTimeUtils.convertToString(row.effectiveFrom());
            lines.add(row.ocName() + " [" + row.rank() + "] " + effectiveFrom
                    + (row.planRowExists() ? "" : " 缺规划行"));
        }
        return String.join("\n", lines);
    }
}
