package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * OC收益查询实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.20
 */
@Component
@RequiredArgsConstructor
public class OcBenefitQueryStrategyImpl extends PnMsgStrategy {
    private final TornFactionOcBenefitDAO benefitDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_BENEFIT;
    }

    @Override
    public String getCommandDescription() {
        return "获取当月OC收益，例g#" + BotCommands.OC_BENEFIT + "(#用户ID)";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornUserDO user = super.getTornUser(sender, msg);
        LocalDateTime fromDate = LocalDate.now().minusDays(LocalDate.now().getDayOfMonth() - 1L)
                .atTime(0, 0, 0);
        List<TornFactionOcBenefitDO> benefitList = benefitDao.lambdaQuery()
                .eq(TornFactionOcBenefitDO::getUserId, user.getId())
                .between(TornFactionOcBenefitDO::getOcFinishTime, fromDate, LocalDateTime.now())
                .orderByDesc(TornFactionOcBenefitDO::getOcFinishTime)
                .list();
        if (benefitList.isEmpty()) {
            return super.buildTextMsg("暂未查询到" + LocalDate.now().getMonthValue() + "月完成的OC");
        }

        return super.buildImageMsg(buildDetailMsg(user, benefitList));
    }

    /**
     * 构建OC收益表格
     */
    private String buildDetailMsg(TornUserDO user, List<TornFactionOcBenefitDO> benefitList) {
        DecimalFormat formatter = new DecimalFormat("#,###");
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(user.getNickname() + "  " + LocalDate.now().getMonthValue() + "月OC收益",
                "", "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 7);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("OC名称", "等级", "状态", "完成时间", "坑位", "成功率", "收益"));
        tableConfig.setSubTitle(1, 7);

        for (int i = 0; i < benefitList.size(); i++) {
            tableConfig.setCellStyle(i + 2, 6, new TableImageUtils.CellStyle().setHorizontalPadding(20));

            TornFactionOcBenefitDO benefit = benefitList.get(i);
            tableData.add(List.of(
                    benefit.getOcName(),
                    benefit.getOcRank().toString(),
                    benefit.getOcStatus(),
                    DateTimeUtils.convertToString(benefit.getOcFinishTime()),
                    benefit.getUserPosition(),
                    benefit.getUserPassRate().toString(),
                    formatter.format(benefit.getBenefitMoney())));
        }

        Long total = benefitList.stream().map(TornFactionOcBenefitDO::getBenefitMoney).reduce(0L, Long::sum);
        tableData.add(List.of(LocalDate.now().getMonthValue() + "月OC收益总计:  " + formatter.format(total),
                "", "", "", "", "", ""));
        int row = benefitList.size() + 2;
        tableConfig.addMerge(row, 0, 1, 7)
                .setCellStyle(row, 0, new TableImageUtils.CellStyle()
                        .setFont(new Font("微软雅黑", Font.BOLD, 14))
                        .setAlignment(TableImageUtils.TextAlignment.RIGHT));

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}