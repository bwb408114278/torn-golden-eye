package pn.torn.goldeneye.msg.strategy.faction.attack;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.PnManageMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwDTO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwRespVO;
import pn.torn.goldeneye.torn.model.faction.rw.TornFactionRwVO;
import pn.torn.goldeneye.torn.service.data.TornRwDataService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.util.List;

/**
 * RW真赛开启策略实现类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.25
 */
@Component
@RequiredArgsConstructor
public class FactionRwStrategyImpl extends PnManageMsgStrategy {
    private final TornApi tornApi;
    private final TornRwDataService rwDataService;
    private final TornSettingFactionManager settingFactionManager;
    private final TornFactionRwDAO rwDao;

    @Override
    public String getCommand() {
        return BotCommands.RW_SIGN;
    }

    @Override
    public String getCommandDescription() {
        return "将当前RW标记为真赛, 开始抓取数据";
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.WAR_COMMANDER;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        long factionId = super.getTornFactionIdBySender(sender);
        TornFactionRwRespVO resp = tornApi.sendRequest(factionId, new TornFactionRwDTO(), TornFactionRwRespVO.class);
        if (resp == null || CollectionUtils.isEmpty(resp.getRwList())) {
            return super.buildTextMsg("未查询到RW数据");
        }

        TornFactionRwVO currentRw = resp.getRwList().getFirst();
        if (currentRw.getEnd() != 0L) {
            return super.buildTextMsg("RW已结束, 请排下一场时再操作");
        }

        TornFactionRwDO data = rwDao.getById(currentRw.getId());
        if (data == null) {
            data = currentRw.convert2DO(factionId);
            rwDao.save(data);
        }

        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
        rwDataService.spiderRwData(currentRw, faction, data.getStartTime());
        return super.buildTextMsg("本场真赛:\n" +
                data.getFactionName() + " VS " + data.getOpponentFactionName() +
                "\n开始时间: " + DateTimeUtils.convertToString(data.getStartTime()) +
                "\n金眼将实时抓取对冲数据并登记到战神榜" +
                "\n祝君武运昌隆!");
    }
}