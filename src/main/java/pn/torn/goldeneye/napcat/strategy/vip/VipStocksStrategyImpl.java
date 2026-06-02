package pn.torn.goldeneye.napcat.strategy.vip;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseVipMsgStrategy;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.torn.stocks.StockTradeAdvice;
import pn.torn.goldeneye.torn.service.user.StockTradeStrategyService;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.JsonUtils;
import pn.torn.goldeneye.utils.image.TableImageUtils;
import pn.torn.goldeneye.utils.image.TextImageUtils;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * VIP股票推荐策略实现类
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.06.01
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VipStocksStrategyImpl extends BaseVipMsgStrategy {
    private final StockTradeStrategyService stockAnalysisService;

    @Override
    public String getCommand() {
        return BotCommands.VIP_STOCK_RECOMMEND;
    }

    @Override
    public String getCommandDescription() {
        return "让金眼开波算命蒙一下怎么炒股赚钱";
    }

    @Override
    protected List<? extends QqMsgParam<?>> handle(TornUserDO user, String msg) {
        List<StockTradeAdvice> analyze = stockAnalysisService.analyze(LocalDateTime.now(), false);
        log.debug(JsonUtils.objToJson(analyze));
        return super.buildImageMsg(this.buildGptStockAnalyzeMsg(analyze));
    }

    private String buildGptStockAnalyzeMsg(List<StockTradeAdvice> analyzeList) {
        if (CollectionUtils.isEmpty(analyzeList)) {
            return TextImageUtils.renderTextToBase64("暂时没有操作建议");
        }

        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        tableData.add(List.of(DateTimeUtils.convertToString(analyzeList.getFirst().analysisTime()) + " 炒股建议",
                "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 6);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("股票代码", "当前价格", "操作建议", "操作评分", "策略解释", "原因"));
        tableConfig.setSubTitle(1, 6);


        for (StockTradeAdvice analyze : analyzeList) {
            tableData.add(List.of(
                    analyze.stocksShortname(),
                    analyze.basePrice().toString(),
                    analyze.getActionName(),
                    String.format("%.0f", analyze.score()),
                    analyze.getStrategyName(),
                    String.join("\n", analyze.reasons())));
        }

        tableData.add(List.of("投资有风险, 方案仅供参考!", "", "", "", "", ""));
        int totalRow = 2 + analyzeList.size();
        tableConfig.addMerge(totalRow, 0, 1, 6);
        tableConfig.setCellStyle(totalRow, 0, new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 14))
                .setAlignment(TableImageUtils.TextAlignment.RIGHT));

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}