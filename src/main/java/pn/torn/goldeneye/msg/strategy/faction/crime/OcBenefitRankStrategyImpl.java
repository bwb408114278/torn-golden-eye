package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingFactionDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitRankDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * OC收益榜策略实现类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.10
 */
@Component
@RequiredArgsConstructor
public class OcBenefitRankStrategyImpl extends PnMsgStrategy {
    private final TornFactionOcBenefitDAO benefitDao;
    private final TornUserDAO userDao;
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
        long userId;
        try {
            userId = super.getTornUserId(sender, msg);
        } catch (BizException e) {
            return super.buildTextMsg(e.getMsg());
        }

        TornUserDO user = userDao.getById(userId);
        if (user == null) {
            return super.buildTextMsg("金蝶不认识你哦");
        }

        LocalDateTime fromDate = LocalDate.now().minusDays(LocalDate.now().getDayOfMonth() - 1L)
                .atTime(0, 0, 0);
        List<TornFactionOcBenefitRankDO> rankingList = benefitDao.queryBenefitRanking(user.getFactionId(),
                fromDate, LocalDateTime.now());
        return super.buildImageMsg(buildRankingMsg(user.getFactionId(), rankingList));
    }

    /**
     * 构建OC收益排行榜表格
     */
    private String buildRankingMsg(long factionId, List<TornFactionOcBenefitRankDO> rankingList) {
        DecimalFormat formatter = new DecimalFormat("#,###");
        TornSettingFactionDO faction = settingFactionDao.getById(factionId);

        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(faction.getFactionShortName() + "  " + LocalDate.now().getMonthValue() + "月OC收益排行榜",
                "", "", ""));
        tableConfig.addMerge(0, 0, 1, 4);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "ID ", "Name", "收益"));
        tableConfig.setSubTitle(1, 4);

        for (int i = 0; i < rankingList.size(); i++) {
            TornFactionOcBenefitRankDO ranking = rankingList.get(i);
            tableConfig.setCellStyle(i + 2, 0, new TableImageUtils.CellStyle()
                            .setVerticalPadding(5).setHorizontalPadding(5))
                    .setCellStyle(i + 2, 1, new TableImageUtils.CellStyle()
                            .setVerticalPadding(5).setHorizontalPadding(20))
                    .setCellStyle(i + 2, 2, new TableImageUtils.CellStyle()
                            .setVerticalPadding(5).setHorizontalPadding(20))
                    .setCellStyle(i + 2, 3, new TableImageUtils.CellStyle()
                            .setVerticalPadding(5).setHorizontalPadding(20));

            tableData.add(List.of(
                    String.valueOf(i + 1),
                    ranking.getUserId().toString(),
                    ranking.getNickname(),
                    formatter.format(ranking.getBenefit())));
        }
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}