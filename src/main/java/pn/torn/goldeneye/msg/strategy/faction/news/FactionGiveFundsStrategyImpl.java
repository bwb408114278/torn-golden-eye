package pn.torn.goldeneye.msg.strategy.faction.news;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.funds.TornFactionGiveFundsDAO;
import pn.torn.goldeneye.repository.model.faction.funds.GiveFundsRankingDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 牛马取钱策略实现类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2026.01.12
 */
@Component
@RequiredArgsConstructor
public class FactionGiveFundsStrategyImpl extends SmthMsgStrategy {
    private final TornSettingFactionManager settingFactionManager;
    private final TornFactionGiveFundsDAO giveFundsDao;

    @Override
    public String getCommand() {
        return BotCommands.FACTION_GIVE_FUNDS;
    }

    @Override
    public String getCommandDescription() {
        return "优质牛马公示, 特此画饼奖励";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        long factionId = super.getTornFactionIdBySender(sender);
        LocalDateTime toDate = LocalDate.now().atTime(7, 59, 59);
        LocalDateTime fromDate = toDate.minusDays(30).plusSeconds(1);
        List<GiveFundsRankingDO> rankingList = giveFundsDao.queryGiveFundsRanking(factionId, fromDate, toDate);
        if (CollectionUtils.isEmpty(rankingList)) {
            return super.buildTextMsg("未找到取钱记录");
        }

        return super.buildImageMsg(buildRankingMsg(factionId, rankingList));
    }

    /**
     * 构建小红毁灭者排行榜表格
     *
     * @return 消息内容
     */
    private String buildRankingMsg(long factionId, List<GiveFundsRankingDO> rankingList) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
        tableData.add(List.of(faction.getFactionShortName() + "近30日优质牛马", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 5);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "ID ", "Name", "取钱次数", "取钱总金额"));
        tableConfig.setSubTitle(1, 5);

        for (int i = 0; i < rankingList.size(); i++) {
            GiveFundsRankingDO ranking = rankingList.get(i);
            tableData.add(List.of(
                    String.valueOf(i + 1),
                    ranking.getHandleUserId().toString(),
                    ranking.getNickname(),
                    ranking.getTotal().toString(),
                    NumberUtils.addDelimiters(ranking.getTotalAmount())));
        }
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}