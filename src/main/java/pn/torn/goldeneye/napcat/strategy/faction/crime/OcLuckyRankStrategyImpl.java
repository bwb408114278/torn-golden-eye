package pn.torn.goldeneye.napcat.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.OcSuccessRankDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * OC欧皇榜策略实现类
 *
 * @author Bai
 * @version 1.2.6
 * @since 2026.06.26
 */
@Component
@RequiredArgsConstructor
public class OcLuckyRankStrategyImpl extends SmthMsgStrategy {
    private final TornSettingFactionManager settingFactionManager;
    private final TornFactionOcSlotDAO slotDao;
    private final TornUserDAO userDao;

    private static final int FACTION_RANK_LIMIT = 30;
    private static final int GLOBAL_RANK_LIMIT = 50;

    @Override
    public String getCommand() {
        return BotCommands.OC_LUCKY_RANK;
    }

    @Override
    public String getCommandDescription() {
        return "查询近90天OC欧皇，格式g#" + BotCommands.OC_LUCKY_RANK + "(#帮派ID，0为总榜)";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        long factionId = StringUtils.hasText(msg) ? super.getTornFactionId(msg) : super.getTornFactionIdBySender(sender);
        int limit = factionId == 0 ? GLOBAL_RANK_LIMIT : FACTION_RANK_LIMIT;

        List<OcSuccessRankDO> rankList = slotDao.querySuccessRanking(factionId, LocalDateTime.now().minusDays(90),
                false, limit);
        if (CollectionUtils.isEmpty(rankList)) {
            return super.buildTextMsg("暂未查询到近90天完成的OC");
        }

        String title = buildTitle(factionId);
        return super.buildImageMsg(OcSuccessRankTableBuilder.buildImage(rankList, title, true,
                factionId == 0, userDao, this::getFactionShortName));
    }

    /**
     * 构建表格标题
     */
    private String buildTitle(long factionId) {
        String factionName = factionId == 0L ? "SMTH" : getFactionShortName(factionId);
        return "近90天" + factionName + " OC欧皇榜";
    }

    /**
     * 构建帮派简称
     */
    private String getFactionShortName(long factionId) {
        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
        return faction == null ? "未知" : faction.getFactionShortName();
    }
}
