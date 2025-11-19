package pn.torn.goldeneye.msg.strategy.faction.news;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.armory.TornFactionItemUsedDAO;
import pn.torn.goldeneye.repository.model.faction.armory.ItemUseRankingDO;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 小红毁灭者策略实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class MedBrokerStrategyImpl extends PnMsgStrategy {
    private final TornSettingFactionManager settingFactionManager;
    private final TornFactionItemUsedDAO itemUsedDao;

    @Override
    public String getCommand() {
        return BotCommands.SMALL_RED_BROKER;
    }

    @Override
    public String getCommandDescription() {
        return "谁吃的小红，赔钱！";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        long factionId = super.getTornFactionId(sender, msg);
        LocalDateTime toDate = LocalDate.now().atTime(7, 59, 59);
        LocalDateTime fromDate = toDate.minusDays(30).plusSeconds(1);
        List<ItemUseRankingDO> rankingList = itemUsedDao.queryItemUseRanking(factionId,
                TornConstants.ITEM_NAME_SMALL_RED, fromDate, toDate);
        long totalCount = itemUsedDao.lambdaQuery()
                .eq(TornFactionItemUsedDO::getFactionId, factionId)
                .eq(TornFactionItemUsedDO::getItemName, TornConstants.ITEM_NAME_SMALL_RED)
                .between(TornFactionItemUsedDO::getUseTime, fromDate, toDate)
                .count();
        return super.buildImageMsg(buildRankingMsg(factionId, rankingList, totalCount));
    }

    /**
     * 构建小红毁灭者排行榜表格
     *
     * @return 消息内容
     */
    private String buildRankingMsg(long factionId, List<ItemUseRankingDO> rankingList, long totalCount) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
        tableData.add(List.of(faction.getFactionShortName() + "近30日小红毁灭者", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 5);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "ID ", "Name", "数量", "总占比"));
        tableConfig.setSubTitle(1, 5);

        for (int i = 0; i < rankingList.size(); i++) {
            ItemUseRankingDO ranking = rankingList.get(i);
            tableData.add(List.of(
                    String.valueOf(i + 1),
                    ranking.getUserId().toString(),
                    ranking.getUserNickname(),
                    ranking.getTotal().toString(),
                    BigDecimal.valueOf(ranking.getTotal())
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP) + "%"));
        }
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}