package pn.torn.goldeneye.msg.strategy.faction.crime.benefit;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitRankDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitUserRankDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcBenefitRankingQuery;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.time.LocalDate;
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
        TornUserDO user = super.getTornUser(sender, "");
        long factionId = super.getTornFactionId(msg);
        if (factionId != 0L) {
            TornSettingFactionDO faction = settingFactionManager.getIdMap().get(factionId);
            if (faction == null) {
                return super.buildTextMsg("请输入正确的帮派ID");
            }
        }

        LocalDate baseMonth = LocalDate.now();
        List<QqMsgParam<?>> resultList = new ArrayList<>(super.buildTextMsg(buildUserRankingMsg(user, baseMonth)));
        resultList.addAll(super.buildImageMsg(ocBenefitRankStrategy.buildRankingMsg(factionId, baseMonth)));
        return resultList;
    }

    /**
     * 构建用户排名信息
     */
    public String buildUserRankingMsg(TornUserDO user, LocalDate date) {
        OcBenefitRankingQuery query = new OcBenefitRankingQuery(user.getId(), date);
        TornFactionOcBenefitUserRankDO ranking = benefitDao.queryBenefitUserRanking(query);
        if (ranking == null) {
            return user.getNickname() + "在" + date.getMonthValue() + "月还没有OC收益";
        }

        TornUserDO prevUser = ranking.getPrevUserId() == null ?
                null : userManager.getUserMap().get(ranking.getPrevUserId());
        return user.getNickname() + "在" + date.getMonthValue() + "月的OC中赚了"
                + NumberUtils.addDelimiters(ranking.getBenefit()) +
                "\n在本帮中排名第" + ranking.getFactionRank() +
                ", 在同期" + ranking.getCohortUsers() + "人中排名第" + ranking.getCohortRank() +
                ", 在SMTH中排名第" + ranking.getOverallRank() +
                (prevUser == null ?
                        "\n恭喜你豪取家族第一名, 大家请认准欧皇入队! " :
                        "\n距离上一名" + prevUser.getNickname() + "[" + prevUser.getId() + "] 还差" +
                                NumberUtils.addDelimiters(ranking.getPrevBenefit() - ranking.getBenefit()));
    }

    /**
     * 构建OC收益排行榜表格
     */
    @Cacheable(value = CacheConstants.KEY_TORN_OC_BENEFIT_RANKING_FACTION, key = "#factionId")
    public String buildRankingMsg(long factionId, LocalDate date) {
        OcBenefitRankingQuery query = new OcBenefitRankingQuery(factionId, 0L, date);
        List<TornFactionOcBenefitRankDO> rankingList = benefitDao.queryBenefitRanking(query);
        String factionName = factionId == 0L ?
                "SMTH" : settingFactionManager.getIdMap().get(factionId).getFactionShortName();

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