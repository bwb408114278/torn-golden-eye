package pn.torn.goldeneye.msg.strategy.faction.crime.benefit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeSummaryDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
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
public class OcBenefitQueryStrategyImpl extends SmthMsgStrategy {
    private final TornFactionOcBenefitDAO benefitDao;
    private final TornFactionOcIncomeDAO incomeDao;
    private final TornFactionOcIncomeSummaryDAO incomeSummaryDao;

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
        boolean calcReassign = user.getFactionId().equals(TornConstants.FACTION_PN_ID);
        LocalDateTime fromDate = LocalDate.now().minusDays(LocalDate.now().getDayOfMonth() - 1L)
                .atTime(0, 0, 0);
        LocalDateTime toDate = LocalDateTime.now();

        List<TornFactionOcIncomeDO> incomeList = !calcReassign ? List.of() :
                incomeDao.lambdaQuery()
                        .eq(TornFactionOcIncomeDO::getUserId, user.getId())
                        .between(TornFactionOcIncomeDO::getOcExecutedTime, fromDate, toDate)
                        .orderByDesc(TornFactionOcIncomeDO::getOcExecutedTime)
                        .list();
        TornFactionOcIncomeSummaryDO incomeSummary = !calcReassign ? null :
                incomeSummaryDao.lambdaQuery()
                        .eq(TornFactionOcIncomeSummaryDO::getUserId, user.getId())
                        .eq(TornFactionOcIncomeSummaryDO::getYearMonth, toDate.format(DateTimeUtils.YEAR_MONTH_FORMATTER))
                        .one();

        List<TornFactionOcBenefitDO> benefitList = benefitDao.lambdaQuery()
                .eq(TornFactionOcBenefitDO::getUserId, user.getId())
                .between(TornFactionOcBenefitDO::getOcFinishTime, fromDate, toDate)
                .notIn(calcReassign, TornFactionOcBenefitDO::getOcName, TornConstants.ROTATION_OC_NAME)
                .orderByDesc(TornFactionOcBenefitDO::getOcFinishTime)
                .list();
        if (benefitList.isEmpty() && incomeList.isEmpty()) {
            return super.buildTextMsg("暂未查询到" + LocalDate.now().getMonthValue() + "月完成的OC");
        }

        return super.buildImageMsg(buildDetailMsg(user, incomeList, incomeSummary, benefitList));
    }

    /**
     * 构建OC收益表格
     */
    private String buildDetailMsg(TornUserDO user, List<TornFactionOcIncomeDO> incomeList,
                                  TornFactionOcIncomeSummaryDO incomeSummary, List<TornFactionOcBenefitDO> benefitList) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(user.getNickname() + "  " + LocalDate.now().getMonthValue() + "月OC收益",
                "", "", "", "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 9);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        buildIncomeTable(tableData, tableConfig, incomeList);
        buildBenefitTable(tableData, tableConfig, benefitList, incomeList);

        Long total = benefitList.stream().map(TornFactionOcBenefitDO::getBenefitMoney).reduce(0L, Long::sum);
        Long income = incomeSummary == null ? 0L : incomeSummary.getFinalIncome();
        tableData.add(List.of(
                LocalDate.now().getMonthValue() + "月OC收益总计:  " +
                        NumberUtils.THOUSAND_DELIMITER.format(total + income),
                "", "", "", "", "", "", "", ""));

        int row = 1 + incomeList.size() + (incomeList.isEmpty() ? 0 : 1)
                + benefitList.size() + (benefitList.isEmpty() ? 0 : 1);
        tableConfig.addMerge(row, 0, 1, 9)
                .setCellStyle(row, 0, new TableImageUtils.CellStyle()
                        .setFont(new Font("微软雅黑", Font.BOLD, 14))
                        .setAlignment(TableImageUtils.TextAlignment.RIGHT));

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 构建OC大锅饭收益
     */
    private void buildIncomeTable(List<List<String>> tableData, TableImageUtils.TableConfig tableConfig,
                                  List<TornFactionOcIncomeDO> incomeList) {
        if (CollectionUtils.isEmpty(incomeList)) {
            return;
        }

        tableData.add(List.of("OC名称", "等级", "状态", "完成时间", "岗位", "成功率", "准备天数", "岗位系数", "工时积分"));
        tableConfig.setSubTitle(1, 9);

        for (TornFactionOcIncomeDO income : incomeList) {
            tableData.add(List.of(
                    income.getOcName(),
                    income.getRank().toString(),
                    Boolean.TRUE.equals(income.getIsSuccess()) ?
                            TornOcStatusEnum.SUCCESSFUL.getCode() : TornOcStatusEnum.FAILURE.getCode(),
                    DateTimeUtils.convertToString(income.getOcExecutedTime()),
                    income.getPosition(),
                    income.getPassRate().toString(),
                    income.getBaseWorkingHours().toString(),
                    income.getCoefficient().toString(),
                    income.getEffectiveWorkingHours().toString()));
        }
    }

    /**
     * 构建普通OC收益
     */
    private void buildBenefitTable(List<List<String>> tableData, TableImageUtils.TableConfig tableConfig,
                                   List<TornFactionOcBenefitDO> benefitList, List<TornFactionOcIncomeDO> incomeList) {
        if (CollectionUtils.isEmpty(benefitList)) {
            return;
        }

        int startRow = incomeList.size() + 1 + (incomeList.isEmpty() ? 0 : 1);
        tableData.add(List.of("OC名称", "等级", "状态", "完成时间", "岗位", "成功率", "收益", "", ""));
        tableConfig.addMerge(startRow, 6, 1, 3)
                .setSubTitle(startRow, 9);

        for (int i = 0; i < benefitList.size(); i++) {
            int dataRow = startRow + i + 1;
            tableConfig.addMerge(dataRow, 6, 1, 3)
                    .setCellStyle(dataRow, 6, new TableImageUtils.CellStyle().setHorizontalPadding(20));

            TornFactionOcBenefitDO benefit = benefitList.get(i);
            tableData.add(List.of(
                    benefit.getOcName(),
                    benefit.getOcRank().toString(),
                    benefit.getOcStatus(),
                    DateTimeUtils.convertToString(benefit.getOcFinishTime()),
                    benefit.getUserPosition(),
                    benefit.getUserPassRate().toString(),
                    NumberUtils.THOUSAND_DELIMITER.format(benefit.getBenefitMoney()),
                    "", ""));
        }
    }
}