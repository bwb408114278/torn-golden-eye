package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 股票通知组合服务 - 将内部英文编码转换为正式中文消息,执行同轮合并与优先级排序
 * <p>
 * 纯计算服务,不注入任何DAO。负责三类消息的组合:
 * <ul>
 *   <li>买入消息: 包含策略/风格/成熟度/风险的中文映射,跟随截止时间与最高跟随价</li>
 *   <li>卖出消息: 包含持有时间、净收益率、关闭原因的中文映射(风险退出不称为止盈)</li>
 *   <li>每日摘要: 正式组合与影子研究的当日汇总</li>
 * </ul>
 *
 * <h3>同轮合并与优先级</h3>
 * <ul>
 *   <li>风险卖出优先于其他卖出,其他卖出优先于买入</li>
 *   <li>同类型最多展示 {@value #MAX_ACTIONS_PER_MESSAGE} 个动作,超过时拆分为续报</li>
 *   <li>卖出动作不可丢弃,超过上限时全部拆分续报</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Component
public class StockNoticeComposeService {
    /**
     * 跟随截止时间偏移分钟数(买入消息发送时间 + 60分钟)
     */
    public static final int FOLLOW_MINUTES = 60;
    /**
     * 跟随最高价乘数(entryReferencePrice × 1.0015)
     */
    public static final BigDecimal FOLLOW_PRICE_MULTIPLIER = new BigDecimal("1.0015");
    /**
     * 单条消息最多展示的动作数(同类型超过则拆分续报)
     */
    public static final int MAX_ACTIONS_PER_MESSAGE = 3;
    /**
     * 组合槽位总数(仅用于Javadoc展示,实际值来自 {@code StockPortfolioService.SLOT_COUNT})
     */
    private static final int SLOT_TOTAL = 5;
    /**
     * 百分比缩放系数(netReturn × 100 转为百分数)
     */
    private static final BigDecimal PERCENT_SCALE = new BigDecimal("100");
    /**
     * 百分比保留小数位
     */
    private static final int PERCENT_SCALE_DIGITS = 2;
    /**
     * 价格保留小数位
     */
    private static final int PRICE_SCALE_DIGITS = 2;
    /**
     * 跟随截止时间格式(yyyy-MM-dd HH:mm)
     */
    private static final DateTimeFormatter FOLLOW_UNTIL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 小时换算分钟
     */
    private static final long MINUTES_PER_HOUR = 60L;
    /**
     * 天换算小时
     */
    private static final long HOURS_PER_DAY = 24L;
    /**
     * 风险卖出优先级权重(最高)
     */
    private static final int PRIORITY_RISK_SELL = 0;
    /**
     * 其他卖出优先级权重
     */
    private static final int PRIORITY_OTHER_SELL = 1;
    /**
     * 买入优先级权重(最低)
     */
    private static final int PRIORITY_BUY = 2;
    /**
     * 买入消息标题模板
     */
    private static final String BUY_TITLE_TEMPLATE = "【VIP股票买入｜批次 %s】";
    /**
     * 卖出消息标题模板
     */
    private static final String SELL_TITLE_TEMPLATE = "【VIP股票卖出｜批次 %s】";

    /**
     * 正数符号前缀
     */
    private static final String POSITIVE_SIGN = "+";
    /**
     * 续报标题后缀
     */
    private static final String CONTINUATION_SUFFIX = "（续）";
    /**
     * 槽位展示格式
     */
    private static final String SLOT_DISPLAY_TEMPLATE = "%d / %d";

    /**
     * 组合买入消息文本
     * <p>
     * 使用枚举 {@code getChineseDisplay()} 将策略、风格、成熟度、风险转换为中文。
     * 跟随截止时间和最高建议跟随价直接从批次冻结字段读取(followUntil/followMaxPrice),
     * 不在组合时重新计算,确保审计快照与实际文本一致。
     * 组合槽位展示为 {@code batch.slotNo / 5}。
     *
     * @param batch         买入批次(须含batchNo、stocksShortname、primaryStrategy、
     *                      entryReferencePrice、stylePrior、styleMaturity、riskLevel、
     *                      followUntil、followMaxPrice、slotNo)
     * @param occupiedSlots 当前已占用槽位数(未使用,槽位展示从批次slotNo读取)
     * @return 中文买入消息文本
     */
    public String composeBuyMessage(TornStockVirtualBatchDO batch, int occupiedSlots) {
        Objects.requireNonNull(batch, "批次不能为空");
        Objects.requireNonNull(batch.getBatchNo(), "批次编号不能为空");

        String strategyChinese = resolveStrategyChinese(batch.getPrimaryStrategy());
        String styleChinese = resolveStyleChinese(batch.getStylePrior());
        String maturityChinese = resolveMaturityChinese(batch.getStyleMaturity());
        String riskChinese = resolveRiskChinese(batch.getRiskLevel());
        BigDecimal entryPrice = nullSafePrice(batch.getEntryReferencePrice());
        LocalDateTime followUntil = batch.getFollowUntil();
        if (followUntil == null || batch.getFollowMaxPrice() == null) {
            throw new IllegalStateException("买入批次跟随字段缺失,禁止生成通知: batchNo=" + batch.getBatchNo());
        }
        BigDecimal followMaxPrice = batch.getFollowMaxPrice()
                .setScale(PRICE_SCALE_DIGITS, RoundingMode.HALF_UP);
        int slotNo = batch.getSlotNo() != null ? batch.getSlotNo() : occupiedSlots;
        String slotDisplay = String.format(SLOT_DISPLAY_TEMPLATE, slotNo, SLOT_TOTAL);

        return String.format(BUY_TITLE_TEMPLATE, batch.getBatchNo()) + "\n" +
                "\n" +
                "股票：" + nullSafeText(batch.getStocksShortname()) + "\n" +
                "买入策略：" + strategyChinese + "\n" +
                "系统参考买价：$" + formatPrice(entryPrice) + "\n" +
                "\n" +
                "股票风格：" + styleChinese + "\n" +
                "成熟度：" + maturityChinese + "\n" +
                "风险等级：" + riskChinese + "\n" +
                "建议跟随截止：" + followUntil.format(FOLLOW_UNTIL_FORMATTER) + "\n" +
                "最高建议跟随价：$" + formatPrice(followMaxPrice) + "\n" +
                "当前组合槽位：" + slotDisplay + "\n" +
                "\n" +
                "本消息属于系统虚拟组合，系统不记录个人持仓。" + "\n" +
                "超过跟随时间或最高建议跟随价后不建议追入。";
    }

    /**
     * 组合卖出消息文本
     * <p>
     * 持有时间 = exitTime - entryTime, 格式化为"X天Y小时";
     * 净收益率格式化为 +0.80% 或 -1.50%;
     * 关闭原因通过 {@link StockCloseTypeEnum#fromCode(String)} 转换为中文;
     * 风险退出不称为止盈,按"风险退出"原文展示。
     * 卖出消息必须携带原买入批次号。
     *
     * @param batch 卖出批次(须含batchNo、stocksShortname、primaryStrategy、
     *              entryReferencePrice、exitReferencePrice、netReturn、
     *              entryTime、exitTime、exitReason)
     * @return 中文卖出消息文本
     */
    public String composeSellMessage(TornStockVirtualBatchDO batch) {
        Objects.requireNonNull(batch, "批次不能为空");
        Objects.requireNonNull(batch.getBatchNo(), "批次编号不能为空");

        String strategyChinese = resolveStrategyChinese(batch.getPrimaryStrategy());
        String closeTypeChinese = resolveCloseTypeChinese(batch.getExitReason());
        BigDecimal entryPrice = nullSafePrice(batch.getEntryReferencePrice());
        BigDecimal exitPrice = nullSafePrice(batch.getExitReferencePrice());
        String holdDuration = formatHoldDuration(batch.getEntryTime(), batch.getExitTime());
        String netReturnText = formatNetReturn(batch.getNetReturn());

        return String.format(SELL_TITLE_TEMPLATE, batch.getBatchNo()) + "\n" +
                "\n" +
                "股票：" + nullSafeText(batch.getStocksShortname()) + "\n" +
                "原买入策略：" + strategyChinese + "\n" +
                "系统参考买价：$" + formatPrice(entryPrice) + "\n" +
                "系统参考卖价：$" + formatPrice(exitPrice) + "\n" +
                "扣除0.1%卖出费后净收益：" + netReturnText + "\n" +
                "系统持有时间：" + holdDuration + "\n" +
                "关闭原因：" + closeTypeChinese + "\n" +
                "\n" +
                "本卖出仅对应批次 " + batch.getBatchNo() + "。" + "\n" +
                "未跟随该批次买入的成员无需操作。";
    }


    /**
     * 组合并合并同轮待发送通知,执行优先级排序与拆分续报
     * <p>
     * 排序优先级: 风险卖出 > 其他卖出 > 买入。
     * 同类型最多展示 {@value #MAX_ACTIONS_PER_MESSAGE} 个动作,超过时拆分为多条续报。
     * 卖出动作不可丢弃,超过上限的全部拆分续报。返回的每条 {@link ComposedMessage}
     * 携带其对应的通知ID列表,便于发送后回写发送状态。
     *
     * @param pendingNotices 待发送通知列表(须含noticeType、batchId)
     * @param batchMap       批次ID到批次DO的映射(用于组合消息内容)
     * @return 组合后的消息列表;入参为空或批次缺失时返回空列表
     */
    public List<ComposedMessage> composeAndMergeNotices(List<TornStockNoticeAuditDO> pendingNotices,
                                                        Map<Long, TornStockVirtualBatchDO> batchMap) {
        if (pendingNotices == null || pendingNotices.isEmpty() || batchMap == null || batchMap.isEmpty()) {
            return List.of();
        }

        List<NoticeWithBatch> enriched = new ArrayList<>(pendingNotices.size());
        for (TornStockNoticeAuditDO notice : pendingNotices) {
            TornStockVirtualBatchDO batch = batchMap.get(notice.getBatchId());
            if (batch == null) {
                log.warn("股票通知组合-通知[{}]未找到关联批次[{}],跳过", notice.getId(), notice.getBatchId());
                continue;
            }
            enriched.add(new NoticeWithBatch(notice, batch, resolvePriority(notice, batch)));
        }
        if (enriched.isEmpty()) {
            return List.of();
        }

        enriched.sort(Comparator.comparingInt(NoticeWithBatch::priority));
        return splitIntoMessages(enriched);
    }

    /**
     * 将排序后的通知按类型分组并拆分为多条消息,每条最多 {@value #MAX_ACTIONS_PER_MESSAGE} 个动作。
     * <p>
     * 同类型的连续通知合并到一条消息,超过上限的部分拆分为续报。
     * 卖出动作不可丢弃,拆分时全部保留。
     *
     * @param enriched 已排序的通知+批次+优先级三元组列表
     * @return 拆分后的组合消息列表
     */
    private List<ComposedMessage> splitIntoMessages(List<NoticeWithBatch> enriched) {
        List<ComposedMessage> result = new ArrayList<>();
        List<NoticeWithBatch> bucket = new ArrayList<>(MAX_ACTIONS_PER_MESSAGE);
        String currentType = null;
        for (NoticeWithBatch item : enriched) {
            String itemType = item.notice().getNoticeType();
            if (currentType == null) {
                currentType = itemType;
            }
            if (!itemType.equals(currentType) || bucket.size() >= MAX_ACTIONS_PER_MESSAGE) {
                flushBucket(result, bucket, currentType);
                bucket = new ArrayList<>(MAX_ACTIONS_PER_MESSAGE);
                currentType = itemType;
            }
            bucket.add(item);
        }
        flushBucket(result, bucket, currentType);
        return result;
    }

    /**
     * 将当前桶内的通知组合为消息并追加到结果列表。
     * <p>
     * 桶为空时直接返回。桶内通知按当前类型组合为单条消息文本;
     * 若桶内通知数大于1,则为合并消息;后续拆分续报由调用方分桶保证。
     *
     * @param result      组合消息结果列表
     * @param bucket      当前桶内的通知三元组列表
     * @param currentType 当前桶的通知类型代码
     */
    private void flushBucket(List<ComposedMessage> result, List<NoticeWithBatch> bucket, String currentType) {
        if (bucket.isEmpty()) {
            return;
        }
        List<Long> noticeIds = bucket.stream().map(n -> n.notice().getId()).toList();
        String text = composeMergedMessage(bucket, currentType);
        result.add(new ComposedMessage(noticeIds, text));
    }

    /**
     * 将同类型的一组通知组合为单条消息文本。
     * <p>
     * 单条通知直接调用对应的消息组合方法;
     * 多条通知合并为带"（续）"后缀的续报,每个动作独占一段,标题统一加续报标识。
     * 仅支持 BUY 与 SELL 类型,DAILY_SUMMARY 类型不参与合并(由调用方单独处理)。
     *
     * @param bucket      同类型通知三元组列表
     * @param currentType 通知类型代码
     * @return 组合后的消息文本
     */
    private String composeMergedMessage(List<NoticeWithBatch> bucket, String currentType) {
        if (bucket.size() == 1) {
            return composeSingleNotice(bucket.getFirst());
        }
        StockNoticeTypeEnum noticeType = StockNoticeTypeEnum.fromCode(currentType);
        StringBuilder sb = new StringBuilder();
        boolean isFirst = true;
        for (NoticeWithBatch item : bucket) {
            if (!isFirst) {
                sb.append("\n").append("---").append("\n");
            }
            isFirst = false;
            sb.append(composeSingleNotice(item));
        }
        sb.append("\n").append(CONTINUATION_SUFFIX);
        log.debug("股票通知组合-合并{}条{}类型通知", bucket.size(), noticeType.getChineseDisplay());
        return sb.toString();
    }

    /**
     * 组合单条通知的消息文本(买入或卖出)。
     *
     * @param item 通知+批次三元组
     * @return 单条消息文本
     */
    private String composeSingleNotice(NoticeWithBatch item) {
        StockNoticeTypeEnum noticeType = StockNoticeTypeEnum.fromCode(item.notice().getNoticeType());
        if (noticeType == StockNoticeTypeEnum.BUY) {
            return composeBuyMessage(item.batch(), SLOT_TOTAL);
        }
        return composeSellMessage(item.batch());
    }

    /**
     * 计算通知优先级权重。
     * <p>
     * 卖出类型且关闭原因为 CLOSED_RISK 时权重为 {@value #PRIORITY_RISK_SELL}(最高);
     * 其他卖出权重为 {@value #PRIORITY_OTHER_SELL};
     * 买入权重为 {@value #PRIORITY_BUY}(最低)。
     *
     * @param notice 通知审计
     * @param batch  关联批次
     * @return 优先级权重(数值越小优先级越高)
     */
    private int resolvePriority(TornStockNoticeAuditDO notice, TornStockVirtualBatchDO batch) {
        StockNoticeTypeEnum noticeType = StockNoticeTypeEnum.fromCode(notice.getNoticeType());
        if (noticeType != StockNoticeTypeEnum.SELL) {
            return PRIORITY_BUY;
        }
        if (batch.getExitReason() != null
                && StockCloseTypeEnum.CLOSED_RISK.getCode().equals(batch.getExitReason())) {
            return PRIORITY_RISK_SELL;
        }
        return PRIORITY_OTHER_SELL;
    }

    /**
     * 将买入策略编码转换为中文展示。
     *
     * @param code 买入策略编码
     * @return 中文展示;入参为空时返回占位文本
     */
    private String resolveStrategyChinese(String code) {
        if (code == null || code.isEmpty()) {
            return "未知策略";
        }
        return StockBuyStrategyEnum.fromCode(code).getChineseDisplay();
    }

    /**
     * 将策略适配风格编码转换为中文展示。
     *
     * @param code 风格编码
     * @return 中文展示;入参为空时返回占位文本
     */
    private String resolveStyleChinese(String code) {
        if (code == null || code.isEmpty()) {
            return "未知风格";
        }
        return StockStrategyFitEnum.fromCode(code).getChineseDisplay();
    }

    /**
     * 将成熟度编码转换为中文展示。
     *
     * @param code 成熟度编码
     * @return 中文展示;入参为空时返回占位文本
     */
    private String resolveMaturityChinese(String code) {
        if (code == null || code.isEmpty()) {
            return "未知成熟度";
        }
        return StockMaturityEnum.fromCode(code).getChineseDisplay();
    }

    /**
     * 将风险等级编码转换为中文展示。
     *
     * @param code 风险等级编码
     * @return 中文展示;入参为空时返回占位文本
     */
    private String resolveRiskChinese(String code) {
        if (code == null || code.isEmpty()) {
            return "未知风险";
        }
        return StockRiskLevelEnum.fromCode(code).getChineseDisplay();
    }

    /**
     * 将关闭类型编码转换为中文展示。
     * <p>
     * 风险退出按"风险退出"原文展示,不称为止盈。
     *
     * @param code 关闭类型编码
     * @return 中文展示;入参为空时返回占位文本
     */
    private String resolveCloseTypeChinese(String code) {
        if (code == null || code.isEmpty()) {
            return "未知原因";
        }
        return StockCloseTypeEnum.fromCode(code).getChineseDisplay();
    }

    /**
     * 格式化持有时间为"X天Y小时"。
     * <p>
     * 入场或出场时间为空时返回占位文本;
     * 出场时间早于入场时间时返回占位文本。
     *
     * @param entryTime 入场时间
     * @param exitTime  出场时间
     * @return 格式化后的持有时间文本
     */
    private String formatHoldDuration(LocalDateTime entryTime, LocalDateTime exitTime) {
        if (entryTime == null || exitTime == null || exitTime.isBefore(entryTime)) {
            return "未知";
        }
        long totalMinutes = Duration.between(entryTime, exitTime).toMinutes();
        long totalHours = totalMinutes / MINUTES_PER_HOUR;
        long days = totalHours / HOURS_PER_DAY;
        long hours = totalHours % HOURS_PER_DAY;
        return days + "天" + hours + "小时";
    }

    /**
     * 格式化净收益率为 +0.80% 或 -1.50% 形式。
     * <p>
     * 入参为null时返回占位文本。正数前加"+",负数自带"-"。
     * 计算方式: netReturn × 100, 保留 {@value #PERCENT_SCALE_DIGITS} 位小数。
     *
     * @param netReturn 净收益率(小数形式,如0.008表示0.8%)
     * @return 格式化后的百分比文本
     */
    private String formatNetReturn(BigDecimal netReturn) {
        if (netReturn == null) {
            return "未知";
        }
        BigDecimal percent = netReturn.multiply(PERCENT_SCALE)
                .setScale(PERCENT_SCALE_DIGITS, RoundingMode.HALF_UP);
        String formatted = percent.abs().toPlainString();
        String sign = percent.signum() >= 0 ? POSITIVE_SIGN : "-";
        return sign + formatted + "%";
    }

    /**
     * 格式化价格为保留 {@value #PRICE_SCALE_DIGITS} 位小数的字符串。
     *
     * @param price 价格
     * @return 格式化后的价格文本;入参为null时返回"0.00"
     */
    private String formatPrice(BigDecimal price) {
        if (price == null) {
            return "0.00";
        }
        return price.setScale(PRICE_SCALE_DIGITS, RoundingMode.HALF_UP).toPlainString();
    }


    /**
     * 返回null安全的价格值,为null时返回0。
     *
     * @param price 原始价格
     * @return 非null价格
     */
    private BigDecimal nullSafePrice(BigDecimal price) {
        return price == null ? BigDecimal.ZERO : price;
    }

    /**
     * 返回null安全的文本值,为null时返回空字符串。
     *
     * @param text 原始文本
     * @return 非null文本
     */
    private String nullSafeText(String text) {
        return text == null ? "" : text;
    }


    /**
     * 组合后的消息值对象。
     * <p>
     * 携带本条消息对应的通知ID列表(用于发送后回写状态)和消息文本。
     *
     * @param noticeIds 本条消息对应的通知ID列表
     * @param text      组合后的中文消息文本
     */
    public record ComposedMessage(List<Long> noticeIds, String text) {
    }

    /**
     * 通知+批次+优先级三元组,用于排序与分桶。
     *
     * @param notice   通知审计DO
     * @param batch    关联批次DO
     * @param priority 优先级权重(数值越小优先级越高)
     */
    private record NoticeWithBatch(TornStockNoticeAuditDO notice,
                                   TornStockVirtualBatchDO batch,
                                   int priority) {
    }
}
