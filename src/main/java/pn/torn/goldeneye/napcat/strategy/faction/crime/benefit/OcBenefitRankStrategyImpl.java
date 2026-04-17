package pn.torn.goldeneye.napcat.strategy.faction.crime.benefit;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitRankDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcBenefitRankingQuery;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * OC收益榜策略实现类
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.09.10
 */
@Component
@RequiredArgsConstructor
public class OcBenefitRankStrategyImpl extends SmthMsgStrategy {
    private final TornSettingFactionManager settingFactionManager;
    private final TornFactionOcBenefitDAO benefitDao;
    @Lazy
    @Resource
    private OcBenefitRankStrategyImpl ocBenefitRankStrategy;

    @Override
    public String getCommand() {
        return BotCommands.OC_BENEFIT_RANK;
    }

    @Override
    public String getCommandDescription() {
        return "让我看看谁的OC赔钱了";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornUserDO user = null;
        long factionId = 0L;
        if ("同期".equals(msg)) {
            user = super.getTornUser(sender, "");
        } else {
            factionId = super.getTornFactionId(msg);
        }

        if (user == null && factionId != 0L) {
            TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
            if (faction == null) {
                return super.buildTextMsg("请输入正确的帮派ID");
            }
        }

        LocalDate baseMonth = LocalDate.now();
        List<TornFactionOcBenefitRankDO> rankList;
        String title;
        if (user != null) {
            OcBenefitRankingQuery query = new OcBenefitRankingQuery(user.getId(), baseMonth,
                    TornConstants.ROTATION_OC_NAME.get(user.getFactionId()));
            rankList = benefitDao.queryCohortBenefitRanking(query);
            String cohort = String.format("%07d", user.getId()).substring(0, 3);
            title = cohort + "同期" + baseMonth.getMonthValue() + "月OC收益排行榜";
        } else {
            OcBenefitRankingQuery query = new OcBenefitRankingQuery(factionId, 0L, baseMonth);
            rankList = benefitDao.queryBenefitRanking(query);
            String factionName = factionId == 0L ?
                    "SMTH" : settingFactionManager.getIdMap().get(factionId).getFactionShortName();
            title = factionName + "  " + baseMonth.getMonthValue() + "月OC收益排行榜";

        }

        return super.buildImageMsg(ocBenefitRankStrategy.buildRankTable(rankList, title));
    }

    /**
     * 构建OC收益排行榜表格
     */
    public String buildRankTable(List<TornFactionOcBenefitRankDO> rankingList, String title) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(title, "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 5);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "ID ", "Name", "帮派", "收益"));
        tableConfig.setSubTitle(1, 5);

        for (int i = 0; i < rankingList.size(); i++) {
            TornFactionOcBenefitRankDO ranking = rankingList.get(i);
            tableConfig.setCellStyle(i + 2, 0, new TableImageUtils.CellStyle()
                            .setVerticalPadding(5).setHorizontalPadding(5))
                    .setCellStyle(i + 2, 1, new TableImageUtils.CellStyle()
                            .setVerticalPadding(5).setHorizontalPadding(20))
                    .setCellStyle(i + 2, 2, new TableImageUtils.CellStyle()
                            .setVerticalPadding(5).setHorizontalPadding(20))
                    .setCellStyle(i + 2, 3, new TableImageUtils.CellStyle()
                            .setVerticalPadding(5).setHorizontalPadding(20))
                    .setCellStyle(i + 2, 4, new TableImageUtils.CellStyle()
                            .setVerticalPadding(5).setHorizontalPadding(20));

            tableData.add(List.of(
                    String.valueOf(i + 1),
                    ranking.getUserId().toString(),
                    userManager.getUserById(ranking.getUserId()).getNickname(),
                    settingFactionManager.getIdMap().get(ranking.getFactionId()).getFactionShortName(),
                    NumberUtils.addDelimiters(ranking.getBenefit())));
        }

        tableData.add(List.of("收益为去掉道具成本后的净收益", "", "", "", ""));
        int totalRow = 2 + rankingList.size();
        tableConfig.addMerge(totalRow, 0, 1, 5);
        tableConfig.setCellStyle(totalRow, 0, new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 14))
                .setAlignment(TableImageUtils.TextAlignment.RIGHT));

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}