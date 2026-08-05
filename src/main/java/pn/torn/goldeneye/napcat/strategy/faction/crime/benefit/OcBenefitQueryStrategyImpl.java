package pn.torn.goldeneye.napcat.strategy.faction.crime.benefit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcBenefitDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeSummaryDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcBenefitUserRankDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.income.OcBenefitRankingQuery;
import pn.torn.goldeneye.torn.service.faction.oc.income.TornOcIncomeService;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.image.ImageCompositor;
import pn.torn.goldeneye.utils.image.TableImageUtils;
import pn.torn.goldeneye.utils.image.TextImageUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * OC收益查询实现类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2025.08.20
 */
@Component
@RequiredArgsConstructor
public class OcBenefitQueryStrategyImpl extends SmthMsgStrategy {
    private final TornOcIncomeService incomeService;
    private final TornFactionOcBenefitDAO benefitDao;
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
        BufferedImage imageTable = buildDetailMsg(user, dataResult);
        String rankingMsg = buildUserRankingMsg(user, monthRange.fromDate().toLocalDate());

        int tableWidth = imageTable.getWidth();
        BufferedImage imgMsg = TextImageUtils.renderTextToImage(rankingMsg,
                new TextImageUtils.TextConfig().setWidth(tableWidth).setAlignment(TextImageUtils.TextAlignment.RIGHT));
        String base64 = ImageCompositor.stitchVerticallyToBase64(List.of(imageTable, imgMsg));
        return super.buildImageMsg(base64);
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
     * 查询OC相关数据。
     *
     * <p>个人大锅饭income与summary查询不受用户当前所属帮派限制：用户可能已更换或退出大锅饭帮派，
     * 指定月份必须保留其全部历史帮派的大锅饭收益，空结果自然返回空列表/{@code null}。普通收益
     * 明细由记录自身的帮派、OC名称与完成时间决定是否排除，同样不锁定当前帮派。</p>
     */
    private OcDataResult queryOcData(TornUserDO user, DateRange dateRange) {
        List<TornFactionOcIncomeDO> incomeList = queryIncomeList(user.getId(), dateRange);
        TornFactionOcIncomeSummaryDO incomeSummary = queryIncomeSummary(user.getId(), dateRange.toDate());
        List<TornFactionOcBenefitDO> benefitList = queryBenefitList(user, dateRange);
        return new OcDataResult(incomeList, incomeSummary, benefitList);
    }

    /**
     * 查询收入列表。
     *
     * <p>个人大锅饭明细按“结算叶子完成月份”查询，与月度汇总口径一致：找出目标月份完成的
     * 全部结算叶子，批量回溯整条链节点，再按用户ID返回这些链节点的全部income。跨月链父节点
     * 参与人的income虽然按父节点自身时间存储，但会随叶子月份一并返回，覆盖用户当月参与过的
     * 全部帮派，不锁定当前帮派。</p>
     */
    private List<TornFactionOcIncomeDO> queryIncomeList(Long userId, DateRange dateRange) {
        String yearMonth = dateRange.toDate().format(DateTimeUtils.YEAR_MONTH_FORMATTER);
        return incomeService.queryUserIncomeBySettlementMonth(userId, yearMonth);
    }

    /**
     * 查询收入汇总。
     *
     * <p>同一用户可能在同月更换帮派，真实数据库允许同一用户同月存在多条不同帮派summary。
     * 此处查询该用户指定月份的全部summary记录并跨帮派聚合，不再调用单对象{@code one()}查询，
     * 避免锁定当前帮派或抛出TooManyResults。</p>
     */
    private TornFactionOcIncomeSummaryDO queryIncomeSummary(Long userId, LocalDateTime toDate) {
        String yearMonth = toDate.format(DateTimeUtils.YEAR_MONTH_FORMATTER);
        List<TornFactionOcIncomeSummaryDO> summaries = incomeSummaryDao.lambdaQuery()
                .eq(TornFactionOcIncomeSummaryDO::getUserId, userId)
                .eq(TornFactionOcIncomeSummaryDO::getYearMonth, yearMonth)
                .list();
        if (CollectionUtils.isEmpty(summaries)) {
            return null;
        }
        return aggregateIncomeSummary(summaries);
    }

    /**
     * 聚合用户同一月份全部历史帮派的收益汇总。
     *
     * <p>对多个帮派的汇总记录按数值字段求和，生成一条跨帮派汇总，供个人收益展示使用。</p>
     *
     * @param summaries 同一用户同一月份的全部帮派汇总
     * @return 跨帮派聚合后的汇总记录
     */
    private TornFactionOcIncomeSummaryDO aggregateIncomeSummary(List<TornFactionOcIncomeSummaryDO> summaries) {
        TornFactionOcIncomeSummaryDO combined = new TornFactionOcIncomeSummaryDO();
        combined.setUserId(summaries.getFirst().getUserId());
        combined.setYearMonth(summaries.getFirst().getYearMonth());
        combined.setTotalEffectiveHours(summaries.stream()
                .map(TornFactionOcIncomeSummaryDO::getTotalEffectiveHours)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        combined.setTotalItemCost(summaries.stream()
                .mapToLong(s -> s.getTotalItemCost() == null ? 0L : s.getTotalItemCost())
                .sum());
        combined.setTotalReward(summaries.stream()
                .mapToLong(s -> s.getTotalReward() == null ? 0L : s.getTotalReward())
                .sum());
        combined.setNetReward(summaries.stream()
                .mapToLong(s -> s.getNetReward() == null ? 0L : s.getNetReward())
                .sum());
        combined.setFinalIncome(summaries.stream()
                .mapToLong(s -> s.getFinalIncome() == null ? 0L : s.getFinalIncome())
                .sum());
        combined.setOcCount(summaries.stream()
                .mapToInt(s -> s.getOcCount() == null ? 0 : s.getOcCount())
                .sum());
        combined.setSuccessOcCount(summaries.stream()
                .mapToInt(s -> s.getSuccessOcCount() == null ? 0 : s.getSuccessOcCount())
                .sum());
        return combined;
    }

    /**
     * 查询普通收益列表，与排行榜共用统一的大锅饭排除规则。
     *
     * <p>加载全部大锅饭帮派的排除规则，由每条普通收益自身的帮派、OC名称和完成时间决定是否
     * 排除，覆盖用户当月参与过的全部历史帮派，不锁定当前帮派。</p>
     */
    private List<TornFactionOcBenefitDO> queryBenefitList(TornUserDO user, DateRange dateRange) {
        OcBenefitRankingQuery query = new OcBenefitRankingQuery(user.getId(),
                dateRange.fromDate(), dateRange.toDate());
        return benefitDao.queryPersonalBenefitList(query);
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
        return user.getNickname() + "在" + date.getMonthValue() + "月的OC中赚了" +
                NumberUtils.addDelimiters(ranking.getBenefit() + ranking.getItemCost()) +
                "(含" + NumberUtils.addDelimiters(ranking.getItemCost()) + "道具成本)" +
                "\n在本帮中排名第" + ranking.getFactionRank() +
                ", 在同期" + ranking.getCohortUsers() + "人中排名第" + ranking.getCohortRank() +
                ", 在SMTH中排名第" + ranking.getOverallRank() +
                (prevUser == null ?
                        "\n恭喜你豪取家族第一名, 大家请认准欧皇入队! " :
                        "\n距离上一名" + prevUser.getNickname() + "[" + prevUser.getId() + "] 还差" +
                                NumberUtils.addDelimiters(ranking.getPrevBenefit() - ranking.getBenefit()));
    }

    /**
     * 构建OC收益表格
     */
    private BufferedImage buildDetailMsg(TornUserDO user, OcDataResult dataResult) {
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
            currentRow = buildIncomeTable(tableData, tableConfig, dataResult.getIncomeList(), currentRow, displayConfig);
        }

        // 添加非大锅饭表格
        if (!dataResult.getBenefitList().isEmpty()) {
            buildBenefitTable(tableData, tableConfig, dataResult.getBenefitList(), currentRow, displayConfig);
        }

        return TableImageUtils.renderTableToImage(tableData, tableConfig);
    }

    /**
     * 创建表格显示配置
     */
    private TableDisplayConfig createDisplayConfig(TornUserDO user) {
        boolean isNoCoefficientReassign = user.getFactionId().equals(TornConstants.FACTION_NOV_ID)
                || user.getFactionId().equals(TornConstants.FACTION_BSU_ID);
        return new TableDisplayConfig(isNoCoefficientReassign);
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
    private void buildBenefitTable(List<List<String>> tableData, TableImageUtils.TableConfig tableConfig,
                                   List<TornFactionOcBenefitDO> benefitList, int startRow,
                                   TableDisplayConfig displayConfig) {
        if (CollectionUtils.isEmpty(benefitList)) {
            return;
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
            List<String> headers = createCommonHeaders();
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
            List<String> headers = createCommonHeaders();
            headers.add("收益");

            // 填充剩余列
            int totalColumns = getTotalColumns();
            for (int i = 7; i < totalColumns; i++) {
                headers.add("");
            }

            return headers;
        }

        /**
         * 创建收入表与收益表共用的前六个表头列。
         *
         * @return 包含OC名称、等级、状态、完成时间、岗位、成功率的新表头列表
         */
        private List<String> createCommonHeaders() {
            List<String> headers = new ArrayList<>();
            headers.add("OC名称");
            headers.add("等级");
            headers.add("状态");
            headers.add("完成时间");
            headers.add("岗位");
            headers.add("成功率");
            return headers;
        }
    }
}