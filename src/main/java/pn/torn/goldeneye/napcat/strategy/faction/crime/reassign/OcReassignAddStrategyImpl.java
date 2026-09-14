package pn.torn.goldeneye.napcat.strategy.faction.crime.reassign;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
import pn.torn.goldeneye.torn.service.faction.oc.income.TornOcBatchIncomeService;
import pn.torn.goldeneye.torn.service.faction.oc.reassign.OcReassignConfigService;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * OC大锅饭添加策略实现类
 *
 * <p>帮派OC指挥官可添加本帮派OC；超管可带帮派ID前缀添加任意帮派。添加成功后异步提交该帮派
 * 的批量收益补算（防重入由批量收益服务保证）。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@Component
@RequiredArgsConstructor
public class OcReassignAddStrategyImpl extends BaseGroupMsgStrategy {
    /**
     * 生效日期参数格式
     */
    private static final Pattern EFFECTIVE_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /**
     * 指令用法示例（可选帮派ID前缀仅超管、可选生效日期默认当月1日）
     */
    private static final String USAGE_EXAMPLE =
            "g#" + BotCommands.OC_REASSIGN_ADD + "(#帮派ID)#OC名称(#yyyy-MM-dd)";

    private final OcReassignConfigService reassignConfigService;
    private final TornSettingFactionManager settingFactionManager;
    private final ProjectProperty projectProperty;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornOcBatchIncomeService ocBatchIncomeService;

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.OC_COMMANDER;
    }

    @Override
    public String getCommand() {
        return BotCommands.OC_REASSIGN_ADD;
    }

    @Override
    public String getCommandDescription() {
        return "添加OC进大锅饭名单, 例" + USAGE_EXAMPLE;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        AddCommand command = parseCommand(sender, msg);
        if (command.failureMessage() != null) {
            return super.buildTextMsg(command.failureMessage());
        }

        OcReassignConfigService.AddOcResult result = reassignConfigService
                .addOc(command.factionId(), command.ocName(), command.effectiveDate(), sender.getUserId());
        if (!result.success()) {
            return super.buildTextMsg(result.failureReason());
        }

        virtualThreadExecutor.execute(() -> ocBatchIncomeService
                .requestBatchIncome(command.factionId(), LocalDateTime.now()));
        return super.buildTextMsg(buildSuccessMsg(command.factionId(), command.ocName(), result));
    }

    /**
     * 解析添加指令参数：可选帮派ID前缀（仅超管）、OC名称、可选生效日期（默认当月1日由服务端兜底）。
     *
     * @param sender 消息发送人
     * @param msg    指令参数
     * @return 解析结果，失败时携带回执文案
     */
    private AddCommand parseCommand(QqRecMsgSender sender, String msg) {
        String[] msgArray = msg.split("#");
        int index = 0;
        long factionId;
        if (msgArray.length > index && NumberUtils.isLong(msgArray[index])) {
            if (!projectProperty.getAdminId().contains(sender.getUserId())) {
                return new AddCommand(0L, null, null, "失败: 仅超管可指定帮派ID");
            }
            factionId = Long.parseLong(msgArray[index]);
            index++;
        } else {
            TornUserDO user = super.getTornUser(sender, "");
            factionId = user.getFactionId() == null ? 0L : user.getFactionId();
        }

        if (msgArray.length < index + 1 || !StringUtils.hasText(msgArray[index])) {
            return new AddCommand(0L, null, null,
                    "参数有误，正确格式：" + USAGE_EXAMPLE);
        }
        String ocName = msgArray[index].trim();
        index++;

        LocalDate effectiveDate = null;
        if (msgArray.length > index) {
            if (msgArray.length > index + 1 || !EFFECTIVE_DATE_PATTERN.matcher(msgArray[index]).matches()) {
                return new AddCommand(0L, null, null,
                        "参数有误，正确格式：" + USAGE_EXAMPLE);
            }
            effectiveDate = LocalDate.parse(msgArray[index]);
        }
        return new AddCommand(factionId, ocName, effectiveDate, null);
    }

    /**
     * 拼装添加成功回执（含规划范围同步状态、规划档案警告与异步补算提示）。
     *
     * @param factionId 目标帮派ID
     * @param ocName    OC名称
     * @param result    添加结果
     * @return 成功回执文本
     */
    private String buildSuccessMsg(long factionId, String ocName, OcReassignConfigService.AddOcResult result) {
        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
        String factionLabel = faction == null ? String.valueOf(factionId) : faction.getFactionShortName();
        StringBuilder builder = new StringBuilder("已加入大锅饭: ").append(factionLabel).append(" - ").append(ocName)
                .append("\n生效时间: ").append(DateTimeUtils.convertToString(result.effectiveFrom()))
                .append("\n规划范围: ").append(result.planSynced() ? "已同步" : "规划行已存在");
        if (result.profileMissing()) {
            builder.append("\n警告: 该OC缺少新队规划档案(torn_setting_oc_plan_profile), 自动规划不会规划该OC");
        }
        return builder.append("\n补算: 已提交异步执行, 稍后可用[").append(BotCommands.OC_REASSIGN_LIST)
                .append("]确认").toString();
    }

    /**
     * 添加指令解析结果。
     *
     * @param factionId      目标帮派ID
     * @param ocName         OC名称
     * @param effectiveDate  生效日期，未指定时为null
     * @param failureMessage 解析失败回执文案，成功时为null
     */
    private record AddCommand(long factionId, String ocName, LocalDate effectiveDate, String failureMessage) {
    }
}
