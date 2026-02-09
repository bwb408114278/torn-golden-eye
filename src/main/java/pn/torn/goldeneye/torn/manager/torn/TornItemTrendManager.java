package pn.torn.goldeneye.torn.manager.torn;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.repository.dao.torn.TornItemHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.ItemHistoryNoticeDO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 物品趋势提醒公共逻辑层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.09
 */
@Component
@RequiredArgsConstructor
public class TornItemTrendManager {
    private final Bot bot;
    private final TornItemHistoryDAO itemHistoryDao;
    private final ProjectProperty projectProperty;
    private static final List<Integer> CONSUME_ITEM_ID_LIST = new ArrayList<>();
    private static final List<Integer> MUSEUM_ITEM_ID_LIST = new ArrayList<>();
    private static final List<Integer> OTHER_ITEM_ID_LIST = new ArrayList<>();
    private static final TableImageUtils.CellStyle STYLE_NORMAL = new TableImageUtils.CellStyle()
            .setBgColor(Color.WHITE).setTextColor(new Color(144, 164, 174));
    private static final TableImageUtils.CellStyle STYLE_PLUS = new TableImageUtils.CellStyle()
            .setBgColor(new Color(224, 247, 250)).setTextColor(new Color(0, 150, 136));
    private static final TableImageUtils.CellStyle STYLE_MINUS = new TableImageUtils.CellStyle()
            .setBgColor(new Color(255, 243, 224)).setTextColor(new Color(230, 81, 0));

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        initConsumeItemIdList();
        initMuseumItemIdList();
        initOtherItemIdList();
    }

    /**
     * 发送趋势消息
     */
    public void sendTrendMsg() {
        LocalDate date = LocalDate.now();

        List<ItemHistoryNoticeDO> historyList = itemHistoryDao.queryItemComparison(CONSUME_ITEM_ID_LIST, date);
        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(projectProperty.getVipGroupId())
                .addMsg(new ImageQqMsg(buildTableData("糖酒饮料", historyList, date)))
                .build();
        bot.sendRequest(param, String.class);

        historyList = itemHistoryDao.queryItemComparison(MUSEUM_ITEM_ID_LIST, date);
        param = new GroupMsgHttpBuilder()
                .setGroupId(projectProperty.getVipGroupId())
                .addMsg(new ImageQqMsg(buildTableData("花偶", historyList, date)))
                .build();
        bot.sendRequest(param, String.class);

        historyList = itemHistoryDao.queryItemComparison(OTHER_ITEM_ID_LIST, date);
        param = new GroupMsgHttpBuilder()
                .setGroupId(projectProperty.getVipGroupId())
                .addMsg(new ImageQqMsg(buildTableData("其他物品", historyList, date)))
                .build();
        bot.sendRequest(param, String.class);
    }

    /**
     * 构建表格数据
     */
    private String buildTableData(String title, List<ItemHistoryNoticeDO> dataList, LocalDate date) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(DateTimeUtils.convertToString(date) + title + "环比市场价播报",
                "", "", "", "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 9);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("物品名称", "今日价格", "上月价格", "上周价格", "一周后价格预测",
                "今日存量", "上月存量", "上周存量", "一周后存量预测"));
        tableConfig.setSubTitle(1, 9);
        for (int i = 0; i < 9; i++) {
            tableConfig.setCellStyle(1, i, new TableImageUtils.CellStyle()
                    .setBgColor(new Color(245, 247, 250)).setTextColor(new Color(38, 50, 56)));
        }

        for (int i = 0; i < dataList.size(); i++) {
            ItemHistoryNoticeDO data = dataList.get(i);
            BigDecimal forecastPrice = getPriceForecast(data);
            BigDecimal forecastCirculation = getCirculationForecast(data);

            tableData.add(List.of(
                    data.getItemName(),
                    NumberUtils.addDelimiters(data.getTodayPrice()),
                    NumberUtils.addDelimiters(data.getLastMonthPrice()) + "(" + setChangeText(data.getLastMonthPriceChange()) + ")",
                    NumberUtils.addDelimiters(data.getLastWeekPrice()) + "(" + setChangeText(data.getLastWeekPriceChange()) + ")",
                    forecastPrice == null ? "趋势不匹配!" :
                            NumberUtils.addDelimiters(forecastPrice) + "(" + setChangeText(data.getLastYearNextWeekPriceChange()) + ")",
                    NumberUtils.addDelimiters(data.getTodayCirculation()),
                    NumberUtils.addDelimiters(data.getLastMonthCirculation()) + "(" + setChangeText(data.getLastMonthCirculationChange()) + ")",
                    NumberUtils.addDelimiters(data.getLastWeekCirculation()) + "(" + setChangeText(data.getLastWeekCirculationChange()) + ")",
                    forecastCirculation == null ? "趋势不匹配!" :
                            NumberUtils.addDelimiters(forecastCirculation) + "(" + setChangeText(data.getLastYearNextWeekCirculationChange()) + ")"));

            int row = i + 2;
            tableConfig.setCellStyle(row, 0, STYLE_NORMAL);
            tableConfig.setCellStyle(row, 1, STYLE_NORMAL);
            setChangeColor(tableConfig, data.getLastMonthPriceChange(), row, 2);
            setChangeColor(tableConfig, data.getLastWeekPriceChange(), row, 3);
            setChangeColor(tableConfig, forecastPrice == null ? null : data.getLastYearNextWeekPriceChange(), row, 4);
            tableConfig.setCellStyle(row, 5, STYLE_NORMAL);
            setChangeColor(tableConfig, data.getLastMonthCirculationChange(), row, 6);
            setChangeColor(tableConfig, data.getLastWeekCirculationChange(), row, 7);
            setChangeColor(tableConfig, forecastCirculation == null ? null : data.getLastYearNextWeekCirculationChange(), row, 8);
        }

        int totalRow = 2 + dataList.size();
        tableData.add(List.of("预测数据是去年的上月——上周——今日涨跌趋势跟今年匹配，根据涨幅做出的预测"));
        tableConfig.addMerge(totalRow, 0, 1, 11);
        tableConfig.setCellStyle(totalRow, 0, new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 14))
                .setAlignment(TableImageUtils.TextAlignment.RIGHT));

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 预测价格变动
     */
    private BigDecimal getPriceForecast(ItemHistoryNoticeDO data) {
        boolean isPriceSameTrend = isSameTrend(
                data.getTodayPrice(), data.getLastWeekPrice(), data.getLastMonthPrice(),
                data.getLastYearPrice(), data.getLastYearLastWeekPrice(), data.getLastYearLastMonthPrice());
        return isPriceSameTrend ?
                data.getLastYearNextWeekPriceChange()
                        .multiply(BigDecimal.valueOf(data.getTodayPrice()))
                        .multiply(BigDecimal.valueOf(0.01))
                        .add(BigDecimal.valueOf(data.getTodayPrice()))
                        .setScale(0, RoundingMode.HALF_UP) :
                null;
    }

    /**
     * 预测存量变动
     */
    private BigDecimal getCirculationForecast(ItemHistoryNoticeDO data) {
        boolean isCirculationSameTrend = isSameTrend(
                data.getTodayCirculation(), data.getLastWeekCirculation(), data.getLastMonthCirculation(),
                data.getLastYearCirculation(), data.getLastYearLastWeekCirculation(), data.getLastYearLastMonthCirculation());
        return isCirculationSameTrend ?
                data.getLastYearNextWeekCirculationChange()
                        .multiply(BigDecimal.valueOf(data.getTodayCirculation()))
                        .multiply(BigDecimal.valueOf(0.01))
                        .add(BigDecimal.valueOf(data.getTodayCirculation()))
                        .setScale(0, RoundingMode.HALF_UP) :
                null;
    }

    /**
     * 判断两组三点数据的趋势是否一致
     *
     * @param current1 当前第一个点
     * @param current2 当前第二个点（较早）
     * @param current3 当前第三个点（最早）
     * @param last1    去年第一个点
     * @param last2    去年第二个点（较早）
     * @param last3    去年第三个点（最早）
     * @return true表示趋势一致
     */
    private boolean isSameTrend(long current1, long current2, long current3,
                                long last1, long last2, long last3) {
        // 计算今年的两个趋势方向
        // 趋势1：当日 vs 上周
        int currentTrend1 = compareTrend(current1, current2);
        // 趋势2：上周 vs 上月
        int currentTrend2 = compareTrend(current2, current3);

        // 计算去年的两个趋势方向
        // 趋势1：去年今日 vs 去年上周
        int lastYearTrend1 = compareTrend(last1, last2);
        // 趋势2：去年上周 vs 去年上月
        int lastYearTrend2 = compareTrend(last2, last3);

        // 判断趋势是否一致：两个趋势都要相同
        return currentTrend1 == lastYearTrend1 && currentTrend2 == lastYearTrend2;
    }

    /**
     * 比较两个值的趋势
     *
     * @param newer 较新的值
     * @param older 较旧的值
     * @return 1表示上涨，-1表示下跌，0表示持平
     */
    private int compareTrend(long newer, long older) {
        return Long.compare(newer, older);
    }

    /**
     * 设置变化文本
     */
    private String setChangeText(BigDecimal number) {
        if (number.compareTo(BigDecimal.ZERO) >= 0) {
            return "+" + NumberUtils.addDelimiters(number) + "%";
        } else {
            return NumberUtils.addDelimiters(number) + "%";
        }
    }

    /**
     * 设置变化的颜色
     */
    private void setChangeColor(TableImageUtils.TableConfig tableConfig, BigDecimal number, int row, int column) {
        if (number == null || number.compareTo(BigDecimal.ZERO) < 0) {
            tableConfig.setCellStyle(row, column, STYLE_MINUS);
        } else {
            tableConfig.setCellStyle(row, column, STYLE_PLUS);
        }
    }

    /**
     * 初始化糖酒饮料消耗品的物品ID
     */
    private void initConsumeItemIdList() {
        CONSUME_ITEM_ID_LIST.add(531);
        CONSUME_ITEM_ID_LIST.add(550);
        CONSUME_ITEM_ID_LIST.add(541);
        CONSUME_ITEM_ID_LIST.add(552);
        CONSUME_ITEM_ID_LIST.add(638);
        CONSUME_ITEM_ID_LIST.add(551);
        CONSUME_ITEM_ID_LIST.add(542);
        CONSUME_ITEM_ID_LIST.add(587);
        CONSUME_ITEM_ID_LIST.add(1039);
        CONSUME_ITEM_ID_LIST.add(151);
        CONSUME_ITEM_ID_LIST.add(586);
        CONSUME_ITEM_ID_LIST.add(556);
        CONSUME_ITEM_ID_LIST.add(529);
        CONSUME_ITEM_ID_LIST.add(634);
        CONSUME_ITEM_ID_LIST.add(528);
        CONSUME_ITEM_ID_LIST.add(36);
        CONSUME_ITEM_ID_LIST.add(527);
        CONSUME_ITEM_ID_LIST.add(553);
        CONSUME_ITEM_ID_LIST.add(987);
        CONSUME_ITEM_ID_LIST.add(986);
        CONSUME_ITEM_ID_LIST.add(985);
        CONSUME_ITEM_ID_LIST.add(533);
        CONSUME_ITEM_ID_LIST.add(555);
        CONSUME_ITEM_ID_LIST.add(554);
        CONSUME_ITEM_ID_LIST.add(532);
        CONSUME_ITEM_ID_LIST.add(530);
    }

    /**
     * 初始化花偶物品ID列表
     */
    private void initMuseumItemIdList() {
        MUSEUM_ITEM_ID_LIST.add(263);
        MUSEUM_ITEM_ID_LIST.add(260);
        MUSEUM_ITEM_ID_LIST.add(272);
        MUSEUM_ITEM_ID_LIST.add(385);
        MUSEUM_ITEM_ID_LIST.add(276);
        MUSEUM_ITEM_ID_LIST.add(282);
        MUSEUM_ITEM_ID_LIST.add(277);
        MUSEUM_ITEM_ID_LIST.add(271);
        MUSEUM_ITEM_ID_LIST.add(267);
        MUSEUM_ITEM_ID_LIST.add(264);
        MUSEUM_ITEM_ID_LIST.add(617);
        MUSEUM_ITEM_ID_LIST.add(384);
        MUSEUM_ITEM_ID_LIST.add(261);
        MUSEUM_ITEM_ID_LIST.add(618);
        MUSEUM_ITEM_ID_LIST.add(273);
        MUSEUM_ITEM_ID_LIST.add(258);
        MUSEUM_ITEM_ID_LIST.add(266);
        MUSEUM_ITEM_ID_LIST.add(268);
        MUSEUM_ITEM_ID_LIST.add(269);
        MUSEUM_ITEM_ID_LIST.add(281);
        MUSEUM_ITEM_ID_LIST.add(274);
    }

    /**
     * 初始化花偶物品ID列表
     */
    private void initOtherItemIdList() {
        OTHER_ITEM_ID_LIST.add(366);
        OTHER_ITEM_ID_LIST.add(367);
        OTHER_ITEM_ID_LIST.add(561);
        OTHER_ITEM_ID_LIST.add(206);
        OTHER_ITEM_ID_LIST.add(870);
        OTHER_ITEM_ID_LIST.add(380);
        OTHER_ITEM_ID_LIST.add(865);
        OTHER_ITEM_ID_LIST.add(396);
        OTHER_ITEM_ID_LIST.add(428);
        OTHER_ITEM_ID_LIST.add(1298);
        OTHER_ITEM_ID_LIST.add(369);
        OTHER_ITEM_ID_LIST.add(370);
        OTHER_ITEM_ID_LIST.add(365);
        OTHER_ITEM_ID_LIST.add(817);
        OTHER_ITEM_ID_LIST.add(815);
        OTHER_ITEM_ID_LIST.add(818);
        OTHER_ITEM_ID_LIST.add(364);
    }
}