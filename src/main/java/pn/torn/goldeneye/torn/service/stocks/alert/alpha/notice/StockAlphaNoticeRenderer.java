package pn.torn.goldeneye.torn.service.stocks.alert.alpha.notice;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeTextFormat;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * α策略买卖通知正文渲染器 - Alpha BUY/SELL正文的唯一文案来源。
 *
 * <p>本类只做纯静态文案渲染,不发送、不落审计、不做幂等:α初始入场与α原子换仓继续复用既有
 * BUY/SELL/ALPHA_REBALANCE通知类型与同一组合、冻结、发送、幂等链,标题由
 * {@code StockNoticeComposeService}统一添加,本类只输出正文。
 *
 * <p>α正文必须可识别α买卖身份(α=0.04、20日反转主因子、1日反弹权重、Top1目标),
 * 且不得出现旧版五槽、旧版质量分、旧版三类BUY策略名与风格/成熟度/风险等级行。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.09
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockAlphaNoticeRenderer {
    /**
     * α主策略展示名,供旧版解析路径识别ALPHA时复用。
     */
    public static final String STRATEGY_DISPLAY = "α=0.04 反转主策略";
    /**
     * ALPHA_REBALANCE 的中文解释,供BUY/SELL/异常关闭三条路径共用。
     */
    public static final String REBALANCE_CLOSE_REASON_DISPLAY = "Alpha目标发生变化";
    /**
     * 买入正文的α因子说明(与策略展示名同处一行)。
     */
    private static final String BUY_FACTOR_DISPLAY = "（20日反转96% + 1日反弹4%）";
    /**
     * 买入正文的Top1目标说明。
     */
    private static final String TOP1_TARGET_DISPLAY = "当前为Top1目标：持仓位于全股票池Top3内则继续保持";

    /**
     * 渲染α买入通知正文。
     * <p>
     * 跟随截止时间与最高建议跟随价直接取批次冻结字段,缺失时按既有fail-closed语义抛出,
     * 禁止生成缺少跟随窗口的α买入通知。
     *
     * @param batch α买入批次(须含batchNo、stocksShortname、entryReferencePrice、followUntil、followMaxPrice)
     * @return α买入通知正文(不含标题)
     * @throws IllegalStateException 跟随字段缺失时抛出
     */
    public static String renderBuy(TornStockVirtualBatchDO batch) {
        Objects.requireNonNull(batch, "批次不能为空");
        Objects.requireNonNull(batch.getBatchNo(), "批次编号不能为空");
        LocalDateTime followUntil = batch.getFollowUntil();
        if (followUntil == null || batch.getFollowMaxPrice() == null) {
            throw new IllegalStateException("买入批次跟随字段缺失,禁止生成通知: batchNo=" + batch.getBatchNo());
        }
        return "股票：" + StockNoticeTextFormat.nullSafeText(batch.getStocksShortname()) + "\n" +
                "买入策略：" + STRATEGY_DISPLAY + BUY_FACTOR_DISPLAY + "\n" +
                "系统参考买价：$" + StockNoticeTextFormat.formatPrice(batch.getEntryReferencePrice()) + "\n" +
                TOP1_TARGET_DISPLAY + "\n" +
                "建议跟随截止：" + StockNoticeTextFormat.formatFollowUntil(followUntil) + "\n" +
                "最高建议跟随价：$" + StockNoticeTextFormat.formatPrice(batch.getFollowMaxPrice()) + "\n" +
                "\n" +
                "本消息属于系统虚拟组合，系统不记录个人持仓。" + "\n" +
                "超过跟随时间或最高建议跟随价后不建议追入。";
    }

    /**
     * 渲染α换仓卖出通知正文。
     * <p>
     * 换仓不是止盈、止损或到期退出,正文必须明确关闭原因为α目标变化,并引用被换出的原BUY批次号,
     * 便于成员识别该卖出仅对应其跟随过的原批次。
     *
     * @param batch α原仓卖出批次(须含batchNo、stocksShortname、entryReferencePrice、
     *              exitReferencePrice、netReturn、entryTime、exitTime)
     * @return α换仓卖出通知正文(不含标题)
     */
    public static String renderSell(TornStockVirtualBatchDO batch) {
        Objects.requireNonNull(batch, "批次不能为空");
        Objects.requireNonNull(batch.getBatchNo(), "批次编号不能为空");
        String closeReasonDisplay = REBALANCE_CLOSE_REASON_DISPLAY
                + "（" + StockAlphaRuleDefinition.EXIT_REASON_REBALANCE + "）";
        return "股票：" + StockNoticeTextFormat.nullSafeText(batch.getStocksShortname()) + "\n" +
                "原买入批次：" + batch.getBatchNo() + "\n" +
                "系统参考买价：" + StockNoticeTextFormat.formatPrice(batch.getEntryReferencePrice()) + "\n" +
                "系统参考卖价：" + StockNoticeTextFormat.formatPrice(batch.getExitReferencePrice()) + "\n" +
                "扣除0.1%卖出费后净收益：" + StockNoticeTextFormat.formatNetReturn(batch.getNetReturn()) + "\n" +
                "系统持有时间：" + StockNoticeTextFormat.formatHoldDuration(batch.getEntryTime(), batch.getExitTime()) + "\n" +
                "关闭原因：" + closeReasonDisplay + "\n" +
                "\n" +
                "本卖出仅对应批次 " + batch.getBatchNo() + "，为α目标变化换仓，不是止盈、止损或到期退出。" + "\n" +
                "未跟随该批次买入的成员无需操作。";
    }
}
