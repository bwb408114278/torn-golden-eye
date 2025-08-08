package pn.torn.goldeneye.msg.strategy.faction.crime;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.service.faction.oc.TornFactionOcService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class OcCheckStrategyImpl extends BaseMsgStrategy {
    private final Bot bot;
    private final DynamicTaskService taskService;
    private final TornFactionOcService ocService;
    private final SysSettingDAO settingDao;
    private final TornFactionOcDAO ocDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_CHECK;
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        ocService.refreshOc();
        return super.buildTextMsg("OC数据校准完成");
    }

    @PostConstruct
    public void init() {
        String lastRefreshTime = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_LOAD);
        LocalDateTime last = DateTimeUtils.convertToDateTime(lastRefreshTime);
        if (last.plusHours(1).isBefore(LocalDateTime.now())) {
            ocService.refreshOc();
        } else {
            taskService.updateTask(TornConstants.TASK_ID_OC_RELOAD, ocService::refreshOc,
                    DateTimeUtils.convertToInstant(last.plusHours(1)), null);
            ocService.updateScheduleTask();
        }

        List<TornFactionOcDO> ocList = ocDao.queryRotationExecList();

        GroupMsgHttpBuilder builder = new GroupMsgHttpBuilder()
                .setGroupId(testProperty.getGroupId())
                .addMsg(new TextGroupMsg("OC轮转队加载完成"));
        if (CollectionUtils.isEmpty(ocList)) {
            builder.addMsg(new TextGroupMsg("\n当前没有轮转队"));
        } else {
            for (TornFactionOcDO oc : ocList) {
                builder.addMsg(new TextGroupMsg("\n" + oc.getRank() + "级: 抢车位时间为" +
                        DateTimeUtils.convertToString(oc.getReadyTime())));
            }
        }

        bot.sendRequest(builder.build(), String.class);
    }
}