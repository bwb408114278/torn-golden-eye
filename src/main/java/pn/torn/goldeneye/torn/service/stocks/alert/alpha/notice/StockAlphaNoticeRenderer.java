package pn.torn.goldeneye.torn.service.stocks.alert.alpha.notice;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeTextFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * α策略买卖通知正文渲染器 - Alpha BUY/SELL正文的唯一文案来源。
 *
 * <p>本类只做纯静态文案渲染,不发送、不落审计、不做幂等:α初始入场与α原子换仓继续复用既有
 * BUY/SELL/ALPHA_REBALANCE通知类型与同一组合、冻结、发送、幂等链,标题由
 * {@code StockNoticeComposeService}统一添加,本类只输出正文。
 * <p>α继续持有通知为正文自包含的独立通知(不参与批次组合、不参与{@code rebalanceAssociationId}组),
 * 标题与正文都由{@link #renderHold(String, LocalDate)}一次性输出,创建时即冻结为最终投递文本。
 *
 * <p>α正文必须可识别α买卖身份(α=0.04、20日反转主因子、1日反弹权重、Top1目标),
 * 且不得出现旧版五槽、旧版质量分、旧版三类BUY策略名与风格/成熟度/风险等级行。
 *
 * @author Bai
 * @version 1.6.6
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
    private static final String TOP1_TARGET_DISPLAY = "当前为Top1目标：虚拟持仓位于全部Stock的Top3内则继续保持";
    /**
     * 继续持有通知标题(正文自包含通知,标题不在{@code StockNoticeComposeService}中添加)。
     */
    public static final String HOLD_TITLE = "【α股票提醒 · 继续持有】";
    /**
     * 继续持有通知的决策业务日展示格式。
     */
    private static final DateTimeFormatter HOLD_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 渲染α买入通知正文。
     * <p>
     * 记录有效期与记录价格上限直接取批次冻结字段,缺失时按既有fail-closed语义抛出,
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
        return "Stock：" + StockNoticeTextFormat.nullSafeText(batch.getStocksShortname()) + "\n" +
                "模型规则：" + STRATEGY_DISPLAY + BUY_FACTOR_DISPLAY + "\n" +
                "记录参考价：$" + StockNoticeTextFormat.formatPrice(batch.getEntryReferencePrice()) + "\n" +
                TOP1_TARGET_DISPLAY + "\n" +
                "记录有效期至：" + StockNoticeTextFormat.formatFollowUntil(followUntil) + "\n" +
                "记录价格上限：$" + StockNoticeTextFormat.formatPrice(batch.getFollowMaxPrice()) + "\n" +
                "\n" +
                "本条为系统虚拟组合的内部记录，不指向任何真实账户操作，" + "\n" +
                "不构成投资建议、买卖要约或跟单依据。";
    }

    /**
     * 渲染α继续持有通知全文(标题+正文)。
     * <p>
     * 触发语义为α决策日目标未变化(持仓仍在Top3内,未产生{@code ALPHA_REBALANCE}):
     * 本条只说明本期不换仓,不是换仓腿,不参与{@code rebalanceAssociationId}组,也不占用两腿成组校验。
     * 文本在通知创建时即固化为最终投递正文,发送链只复用冻结文本,不重新渲染。
     *
     * @param stocksShortname      当前持仓股票简称
     * @param decisionBusinessDate 决策业务日(排名窗口最后共同有效日)
     * @return 继续持有通知全文(含标题)
     */
    public static String renderHold(String stocksShortname, LocalDate decisionBusinessDate) {
        Objects.requireNonNull(decisionBusinessDate, "决策业务日不能为空");
        return HOLD_TITLE + "\n" +
                "\n" +
                "决策日：" + decisionBusinessDate.format(HOLD_DATE_FORMATTER) + "（08:00 决策 → 08:15 执行桶）\n" +
                "当前持仓：" + StockNoticeTextFormat.nullSafeText(stocksShortname) + "（仍在 Top3 内，Top1 未变化）\n" +
                "处理：本期不换仓，继续持有原批次。\n" +
                "\n" +
                "未持有该标的的成员无需操作。";
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
        return "Stock：" + StockNoticeTextFormat.nullSafeText(batch.getStocksShortname()) + "\n" +
                "原买入批次：" + batch.getBatchNo() + "\n" +
                "记录参考价：" + StockNoticeTextFormat.formatPrice(batch.getEntryReferencePrice()) + "\n" +
                "记录结束价：" + StockNoticeTextFormat.formatPrice(batch.getExitReferencePrice()) + "\n" +
                "扣除0.1%费率后系统记录净变化：" + StockNoticeTextFormat.formatNetReturn(batch.getNetReturn()) + "\n" +
                "记录持有区间：" + StockNoticeTextFormat.formatHoldDuration(batch.getEntryTime(), batch.getExitTime()) + "\n" +
                "记录结束原因：" + closeReasonDisplay + "\n" +
                "\n" +
                "本条为系统虚拟组合的内部记录，不构成投资建议。";
    }
}
