package pn.torn.goldeneye.msg.strategy.faction.crime.benefit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitRankDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * OC收益榜策略实现类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.09.10
 */
@Component
@RequiredArgsConstructor
public class OcBenefitRankStrategyImpl extends SmthMsgStrategy {
    private final TornSettingFactionManager settingFactionManager;
    private final TornFactionOcBenefitDAO benefitDao;
    private final TornSettingFactionDAO settingFactionDao;

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
        long factionId = super.getTornFactionId(msg);
        if (factionId != 0L) {
            TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
            if (faction == null) {
                return super.buildTextMsg("请输入正确的帮派ID");
            }
        }

        LocalDate baseMonth = LocalDate.now();
        LocalDateTime fromDate = baseMonth.withDayOfMonth(1).atTime(0, 0, 0);
        LocalDateTime toDate = baseMonth.withDayOfMonth(baseMonth.lengthOfMonth())
                .atTime(23, 59, 59);

        String yearMonth = toDate.format(DateTimeUtils.YEAR_MONTH_FORMATTER);
        List<TornFactionOcBenefitRankDO> rankingList;
        if (factionId == 0L) {
            rankingList = benefitDao.queryAllBenefitRanking(yearMonth, fromDate, toDate,
                    TornConstants.REASSIGN_OC_FACTION, TornConstants.ROTATION_OC_NAME);
        } else if (TornConstants.REASSIGN_OC_FACTION.contains(factionId)) {
            rankingList = benefitDao.queryIncomeRanking(factionId, fromDate, toDate,
                    TornConstants.ROTATION_OC_NAME, yearMonth);
        } else {
            rankingList = benefitDao.queryBenefitRanking(factionId, fromDate, toDate);
        }

        return super.buildImageMsg(buildRankingMsg(factionId, baseMonth, rankingList));
    }

    /**
     * 构建OC收益排行榜表格
     */
    private String buildRankingMsg(long factionId, LocalDate date, List<TornFactionOcBenefitRankDO> rankingList) {
        String factionName = factionId == 0L ? "SMTH" : settingFactionDao.getById(factionId).getFactionShortName();

        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(factionName + "  " + date.getMonthValue() + "月OC收益排行榜", "", "", "", ""));
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
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}