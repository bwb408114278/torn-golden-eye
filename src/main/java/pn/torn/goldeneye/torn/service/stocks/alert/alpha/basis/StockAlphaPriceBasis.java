package pn.torn.goldeneye.torn.service.stocks.alert.alpha.basis;

import pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution.StockAlphaExecutionBarPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaDailyCloseCalculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * α策略价格口径 - 把"已结束自然日序列与本轮决策bar"收敛为一份可排名的收盘序列。
 * <p>
 * 本接口是口径语义的唯一宿主:排名输入序列如何构造只由实现类决定,决策服务不得内联
 * if/else 判断口径,也不得为实现复制第二份排名计算({@code StockAlphaRankingCalculator}仍是唯一实现)。
 * <p>
 * 生产目标只消费 {@link StockAlphaPriceBasisRegistry#productionBasis()};其余口径只写观察列,
 * 不得反向影响生产决策、下单、通知内容与发送时刻。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.18
 */
public interface StockAlphaPriceBasis {

    /**
     * 返回口径编码。
     * <p>
     * 编码是观察列来源摘要与注册表查找的唯一键,不得与其它口径重复。
     *
     * @return 口径编码
     */
    String code();

    /**
     * 基于已结束自然日序列与本轮决策bar产出该口径的收盘序列。
     * <p>
     * 返回序列必须满足{@code StockAlphaRankingCalculator}的输入约束(固定股票池成员齐全、
     * 每支股票至少21个正收盘价);无法产出完整序列时返回空映射,由调用方按各自口径
     * 决定是fail-closed还是仅写空观察列。
     *
     * @param input 口径输入
     * @return 股票ID到按时间升序收盘价序列的映射;该口径无法形成完整序列时返回空映射
     */
    Map<Integer, List<BigDecimal>> closeSeries(StockAlphaBasisInput input);

    /**
     * 价格口径输入 - 决策服务一次性加载并共享给全部口径的只读事实。
     *
     * @param rankingDates 成员完整的排名自然日(升序)
     * @param daily        自然日到股票ID收盘结果的映射
     * @param decisionBars 本轮决策时点各股票的决策bar事实;仅观察口径使用
     */
    record StockAlphaBasisInput(
            List<LocalDate> rankingDates,
            Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily,
            Map<Integer, StockAlphaExecutionBarPolicy.DecisionBar> decisionBars) {

        /**
         * 构造现行(PREVIOUS_CLOSE)口径的收盘序列。
         * <p>
         * 直接按成员完整的已结束自然日顺序拼接决策时点前的收盘价,不追加任何本轮bar价格,
         * 与现行生产决策完全一致;返回的列表可变,供观察口径在其副本语义上追加新增日。
         *
         * @return 股票ID到按时间升序收盘价序列的映射;无可用自然日时返回空映射
         */
        public Map<Integer, List<BigDecimal>> previousCloseSeries() {
            Map<Integer, List<BigDecimal>> closes = new HashMap<>();
            if (rankingDates == null || daily == null) {
                return closes;
            }
            for (LocalDate date : rankingDates) {
                Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> byStock = daily.get(date);
                if (byStock == null) {
                    continue;
                }
                for (Map.Entry<Integer, StockAlphaDailyCloseCalculator.CloseResult> entry : byStock.entrySet()) {
                    closes.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                            .add(entry.getValue().closePrice());
                }
            }
            return closes;
        }
    }
}
