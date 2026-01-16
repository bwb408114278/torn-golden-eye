package pn.torn.goldeneye.msg.strategy.faction.crime.benefit;

import lombok.AllArgsConstructor;
import lombok.Data;
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
 * @version 0.5.0
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
        DateRange monthRange = getCurrentMonthRange();
        OcDataResult dataResult = queryOcData(user, monthRange);

        if (dataResult.isEmpty()) {
            return super.buildTextMsg("暂未查询到" + LocalDate.now().getMonthValue() + "月完成的OC");
        }

        // 构建并返回图片消息
        String imageBase64 = buildDetailMsg(user, dataResult);
        return super.buildImageMsg(imageBase64);
    }

    /**
     * 获取当前月份的时间范围
     */
    private DateRange getCurrentMonthRange() {
        LocalDate today = LocalDate.now();
        LocalDateTime fromDate = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime toDate = LocalDateTime.now();
        return new DateRange(fromDate, toDate);
    }

    /**
     * 查询OC相关数据
     */
    private OcDataResult queryOcData(TornUserDO user, DateRange dateRange) {
        boolean shouldCalcReassign = TornConstants.REASSIGN_OC_FACTION.contains(user.getFactionId());
        List<TornFactionOcIncomeDO> incomeList = shouldCalcReassign ?
                queryIncomeList(user.getId(), dateRange) : List.of();
        TornFactionOcIncomeSummaryDO incomeSummary = shouldCalcReassign ?
                queryIncomeSummary(user.getId(), dateRange.toDate()) : null;
        List<TornFactionOcBenefitDO> benefitList = queryBenefitList(user.getId(), dateRange, shouldCalcReassign);
        return new OcDataResult(incomeList, incomeSummary, benefitList);
    }

    /**
     * 查询收入列表
     */
    private List<TornFactionOcIncomeDO> queryIncomeList(Long userId, DateRange dateRange) {
        return incomeDao.lambdaQuery()
                .eq(TornFactionOcIncomeDO::getUserId, userId)
                .between(TornFactionOcIncomeDO::getOcExecutedTime, dateRange.fromDate(), dateRange.toDate())
                .orderByDesc(TornFactionOcIncomeDO::getOcExecutedTime)
                .list();
    }

    /**
     * 查询收入汇总
     */
    private TornFactionOcIncomeSummaryDO queryIncomeSummary(Long userId, LocalDateTime toDate) {
        String yearMonth = toDate.format(DateTimeUtils.YEAR_MONTH_FORMATTER);
        return incomeSummaryDao.lambdaQuery()
                .eq(TornFactionOcIncomeSummaryDO::getUserId, userId)
                .eq(TornFactionOcIncomeSummaryDO::getYearMonth, yearMonth)
                .one();
    }

    /**
     * 查询收益列表
     */
    private List<TornFactionOcBenefitDO> queryBenefitList(Long userId, DateRange dateRange, boolean shouldCalcReassign) {
        return benefitDao.lambdaQuery()
                .eq(TornFactionOcBenefitDO::getUserId, userId)
                .between(TornFactionOcBenefitDO::getOcFinishTime, dateRange.fromDate(), dateRange.toDate())
                .notIn(shouldCalcReassign, TornFactionOcBenefitDO::getOcName, TornConstants.ROTATION_OC_NAME)
                .orderByDesc(TornFactionOcBenefitDO::getOcFinishTime)
                .list();
    }

    /**
     * 构建OC收益表格
     */
    private String buildDetailMsg(TornUserDO user, OcDataResult dataResult) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        // 创建表格配置
        TableDisplayConfig displayConfig = createDisplayConfig(user);
        int totalColumns = displayConfig.getTotalColumns();

        // 添加标题行
        addTitleRow(tableData, tableConfig, user.getNickname(), totalColumns);

        // 添加大锅饭表格
        int currentRow = 1;
        if (!dataResult.getIncomeList().isEmpty()) {
            currentRow = buildIncomeTable(tableData, tableConfig,
                    dataResult.getIncomeList(), currentRow, displayConfig);
        }

        // 添加非大锅饭表格
        if (!dataResult.getBenefitList().isEmpty()) {
            currentRow = buildBenefitTable(tableData, tableConfig,
                    dataResult.getBenefitList(), currentRow, displayConfig);
        }

        // 添加总计行
        addTotalRow(tableData, tableConfig, dataResult, currentRow, totalColumns);
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 创建表格显示配置
     */
    private TableDisplayConfig createDisplayConfig(TornUserDO user) {
        boolean isNov = user.getFactionId().equals(TornConstants.FACTION_NOV_ID);
        return new TableDisplayConfig(isNov);
    }

    /**
     * 添加标题行
     */
    private void addTitleRow(List<List<String>> tableData, TableImageUtils.TableConfig tableConfig,
                             String nickname, int totalColumns) {
        int month = LocalDate.now().getMonthValue();
        String title = nickname + "  " + month + "月OC收益";

        List<String> titleRow = new ArrayList<>();
        titleRow.add(title);
        for (int i = 1; i < totalColumns; i++) {
            titleRow.add("");
        }
        tableData.add(titleRow);
        tableConfig.addMerge(0, 0, 1, totalColumns);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));
    }

    /**
     * 构建OC大锅饭收益表格
     */
    private int buildIncomeTable(List<List<String>> tableData, TableImageUtils.TableConfig tableConfig,
                                 List<TornFactionOcIncomeDO> incomeList, int startRow,
                                 TableDisplayConfig displayConfig) {
        if (CollectionUtils.isEmpty(incomeList)) {
            return startRow;
        }

        // 添加表头
        List<String> headers = displayConfig.getIncomeHeaders();
        tableData.add(headers);
        tableConfig.setSubTitle(startRow, headers.size());

        // 添加数据行
        for (TornFactionOcIncomeDO income : incomeList) {
            List<String> row = buildIncomeRow(income, displayConfig);
            tableData.add(row);
        }

        return startRow + incomeList.size() + 1;
    }

    /**
     * 构建收入数据行
     */
    private List<String> buildIncomeRow(TornFactionOcIncomeDO income, TableDisplayConfig displayConfig) {
        List<String> row = new ArrayList<>();
        row.add(income.getOcName());
        row.add(income.getRank().toString());
        row.add(Boolean.TRUE.equals(income.getIsSuccess()) ?
                TornOcStatusEnum.SUCCESSFUL.getCode() : TornOcStatusEnum.FAILURE.getCode());
        row.add(DateTimeUtils.convertToString(income.getOcExecutedTime()));
        row.add(income.getPosition());
        row.add(income.getPassRate().toString());
        row.add(income.getBaseWorkingHours().toString());

        // 根据配置决定是否添加岗位系数和工时积分
        if (displayConfig.shouldShowCoefficientColumns()) {
            row.add(income.getCoefficient().toString());
            row.add(income.getEffectiveWorkingHours().toString());
        }

        return row;
    }

    /**
     * 构建普通OC收益表格
     */
    private int buildBenefitTable(List<List<String>> tableData, TableImageUtils.TableConfig tableConfig,
                                  List<TornFactionOcBenefitDO> benefitList, int startRow,
                                  TableDisplayConfig displayConfig) {
        if (CollectionUtils.isEmpty(benefitList)) {
            return startRow;
        }

        int totalColumns = displayConfig.getTotalColumns();
        int benefitColumnStart = 6;
        int benefitColumnSpan = totalColumns - benefitColumnStart;

        // 添加表头
        List<String> headers = displayConfig.getBenefitHeaders();
        tableData.add(headers);
        tableConfig.addMerge(startRow, benefitColumnStart, 1, benefitColumnSpan);
        tableConfig.setSubTitle(startRow, totalColumns);

        // 添加数据行
        for (int i = 0; i < benefitList.size(); i++) {
            int dataRow = startRow + i + 1;
            TornFactionOcBenefitDO benefit = benefitList.get(i);

            List<String> row = buildBenefitRow(benefit, totalColumns);
            tableData.add(row);

            tableConfig.addMerge(dataRow, benefitColumnStart, 1, benefitColumnSpan);
            tableConfig.setCellStyle(dataRow, benefitColumnStart,
                    new TableImageUtils.CellStyle().setHorizontalPadding(20));
        }

        return startRow + benefitList.size() + 1;
    }

    /**
     * 构建收益数据行
     */
    private List<String> buildBenefitRow(TornFactionOcBenefitDO benefit, int totalColumns) {
        List<String> row = new ArrayList<>();
        row.add(benefit.getOcName());
        row.add(benefit.getOcRank().toString());
        row.add(benefit.getOcStatus());
        row.add(DateTimeUtils.convertToString(benefit.getOcFinishTime()));
        row.add(benefit.getUserPosition());
        row.add(benefit.getUserPassRate().toString());
        row.add(NumberUtils.THOUSAND_DELIMITER.format(benefit.getBenefitMoney()));

        // 填充剩余列
        for (int i = 7; i < totalColumns; i++) {
            row.add("");
        }

        return row;
    }

    /**
     * 添加总计行
     */
    private void addTotalRow(List<List<String>> tableData, TableImageUtils.TableConfig tableConfig,
                             OcDataResult dataResult,
                             int row,
                             int totalColumns) {
        long totalBenefit = dataResult.getBenefitList().stream()
                .map(TornFactionOcBenefitDO::getBenefitMoney)
                .reduce(0L, Long::sum);

        long totalIncome = dataResult.getIncomeSummary() != null ?
                dataResult.getIncomeSummary().getFinalIncome() : 0L;

        int month = LocalDate.now().getMonthValue();
        String totalText = month + "月OC收益总计:" + NumberUtils.THOUSAND_DELIMITER.format(totalBenefit + totalIncome);

        List<String> totalRow = new ArrayList<>();
        totalRow.add(totalText);
        for (int i = 1; i < totalColumns; i++) {
            totalRow.add("");
        }

        tableData.add(totalRow);

        tableConfig.addMerge(row, 0, 1, totalColumns);
        tableConfig.setCellStyle(row, 0, new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 14))
                .setAlignment(TableImageUtils.TextAlignment.RIGHT));
    }

    /**
     * 日期范围
     */
    private record DateRange(LocalDateTime fromDate, LocalDateTime toDate) {
    }

    /**
     * OC数据查询结果
     */
    @Data
    @AllArgsConstructor
    private static class OcDataResult {
        private List<TornFactionOcIncomeDO> incomeList;
        private TornFactionOcIncomeSummaryDO incomeSummary;
        private List<TornFactionOcBenefitDO> benefitList;

        public boolean isEmpty() {
            return CollectionUtils.isEmpty(benefitList) && CollectionUtils.isEmpty(incomeList);
        }
    }

    /**
     * 表格显示配置
     */
    private record TableDisplayConfig(boolean isNovFaction) {
        /**
         * 获取总列数
         */
        public int getTotalColumns() {
            return isNovFaction ? 7 : 9;
        }

        /**
         * 是否显示收入表的额外列（岗位系数和工时积分）
         */
        public boolean shouldShowCoefficientColumns() {
            return !isNovFaction;
        }

        /**
         * 获取收入表表头
         */
        public List<String> getIncomeHeaders() {
            List<String> headers = new ArrayList<>();
            headers.add("OC名称");
            headers.add("等级");
            headers.add("状态");
            headers.add("完成时间");
            headers.add("岗位");
            headers.add("成功率");
            headers.add("准备天数");

            if (shouldShowCoefficientColumns()) {
                headers.add("岗位系数");
                headers.add("工时积分");
            }

            return headers;
        }

        /**
         * 获取收益表表头
         */
        public List<String> getBenefitHeaders() {
            List<String> headers = new ArrayList<>();
            headers.add("OC名称");
            headers.add("等级");
            headers.add("状态");
            headers.add("完成时间");
            headers.add("岗位");
            headers.add("成功率");
            headers.add("收益");

            // 填充剩余列
            int totalColumns = getTotalColumns();
            for (int i = 7; i < totalColumns; i++) {
                headers.add("");
            }

            return headers;
        }
    }
}