package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcManager;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcMsgManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 获取OC临时轮转队策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.15
 */
@Component
@RequiredArgsConstructor
public class TempOcStrategyImpl extends BaseMsgStrategy {
    private final TornFactionOcManager ocManager;
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcDAO ocDao;
    private final SysSettingDAO settingDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_TEMP_QUERY;
    }

    @Override
    public String getCommandDescription() {
        return "查询OC临时轮转队";
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(long groupId, String msg) {
        if (!ocManager.isCheckEnableTemp()) {
            return super.buildTextMsg("当前未启用临时轮转队");
        }

        String recId = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_REC_ID + "TEMP");
        String planId = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_PLAN_ID + "TEMP");
        if (recId == null && planId == null) {
            return super.buildTextMsg("未查询到临时轮转队");
        }

        List<Long> teamIdList = new ArrayList<>();
        teamIdList.add(Long.parseLong(planId));
        if (recId != null) {
            String[] teamIdArray = recId.split(",");
            teamIdList.addAll(Arrays.stream(teamIdArray).map(Long::parseLong).toList());
        }

        List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                .in(TornFactionOcDO::getId, teamIdList)
                .orderByAsc(TornFactionOcDO::getName)
                .orderByAsc(TornFactionOcDO::getStatus)
                .orderByDesc(TornFactionOcDO::getReadyTime)
                .list();
        if (CollectionUtils.isEmpty(ocList)) {
            return super.buildTextMsg("未查询到临时轮转队");
        }

        return super.buildImageMsg(msgManager.buildOcTable("临时轮转队执行中OC", ocList));
    }
}