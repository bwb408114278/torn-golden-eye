package pn.torn.goldeneye.msg.strategy.faction.news;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.armory.TornFactionItemUsedDAO;
import pn.torn.goldeneye.repository.model.faction.armory.ItemUseRankingDO;
import pn.torn.goldeneye.repository.model.faction.armory.TornFactionItemUsedDO;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
public class MedBrokerStrategyImpl extends PnMsgStrategy {
    private final TornFactionItemUsedDAO itemUsedDao;

    @Override
    public String getCommand() {
        return BotCommands.SMALL_RED_BROKER;
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        LocalDateTime toDate = LocalDate.now().atTime(7, 59, 59);
        LocalDateTime fromDate = toDate.minusDays(30).plusSeconds(1);
        List<ItemUseRankingDO> rankingList = itemUsedDao.queryItemUseRanking(TornConstants.ITEM_NAME_SMALL_RED,
                fromDate, toDate);
        long totalCount = itemUsedDao.lambdaQuery()
                .eq(TornFactionItemUsedDO::getItemName, TornConstants.ITEM_NAME_SMALL_RED)
                .between(TornFactionItemUsedDO::getUseTime, fromDate, toDate)
                .count();
        return super.buildImageMsg(buildRankingMsg(rankingList, totalCount));
    }

    /**
     * 构建OC列表消息
     *
     * @return 消息内容
     */
    private String buildRankingMsg(List<ItemUseRankingDO> rankingList, long totalCount) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of("PHN近30日小红毁灭者", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 5);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "ID ", "Name", "数量", "总占比"));
        TableImageUtils.CellStyle subTitleStyle = new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 16));
        tableConfig.setCellStyle(1, 0, subTitleStyle);
        tableConfig.setCellStyle(1, 1, subTitleStyle);
        tableConfig.setCellStyle(1, 2, subTitleStyle);
        tableConfig.setCellStyle(1, 3, subTitleStyle);
        tableConfig.setCellStyle(1, 4, subTitleStyle);

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