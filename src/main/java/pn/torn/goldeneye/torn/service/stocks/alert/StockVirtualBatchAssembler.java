package pn.torn.goldeneye.torn.service.stocks.alert;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchEntryFields;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchSignalFields;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeComposeService;

import java.math.BigDecimal;

/**
 * 虚拟批次字段组装器，将服务层事实转换为数据库批次字段。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.29
 */
public final class StockVirtualBatchAssembler {

    /**
     * 入场超时宽限分钟数。
     */
    private static final int ENTRY_STALE_GRACE_MINUTES = 35;

    private StockVirtualBatchAssembler() {
    }

    /**
     * 应用信号阶段字段。
     *
     * @param batch  批次DO
     * @param fields 信号字段
     */
    public static void applySignalFields(TornStockVirtualBatchDO batch,
                                         TornStockVirtualBatchSignalFields fields) {
        batch.setSignalReferencePrice(fields.getSignalReferencePrice());
        batch.setSignalTime(fields.getSignalTime());
        batch.setExpectedEntryBarTime(fields.getSignalTime() == null ? null
                : fields.getSignalTime().plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES));
        batch.setEntryStaleAt(fields.getSignalTime() == null ? null
                : fields.getSignalTime().plusMinutes(ENTRY_STALE_GRACE_MINUTES));
        batch.setStylePrior(fields.getStylePrior());
        batch.setStyleMaturity(fields.getStyleMaturity());
        batch.setRiskLevel(fields.getRiskLevel());
        batch.setStyleEffectiveMonth(fields.getStyleEffectiveMonth());
        batch.setBuyRuleVersion(fields.getBuyRuleVersion());
        batch.setSellRuleVersion(StockRoundTransactionService.SELL_RULE_VERSION);
        batch.setStyleRuleVersion(StockRoundTransactionService.STYLE_RULE_VERSION);
        batch.setRiskRuleVersion(StockRoundTransactionService.RISK_RULE_VERSION);
        batch.setAllocationRuleVersion(StockRoundTransactionService.ALLOCATION_RULE_VERSION);
        batch.setMessageRuleVersion(StockRoundTransactionService.MESSAGE_RULE_VERSION);
        batch.setResetObserved(false);
    }

    /**
     * 应用成交入场字段。
     *
     * @param batch  批次DO
     * @param fields 成交入场字段
     */
    public static void applyFilledEntryFields(TornStockVirtualBatchDO batch,
                                              TornStockVirtualBatchEntryFields fields) {
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        batch.setEntryReferencePrice(fields.getEntryReferencePrice());
        batch.setEntryTime(fields.getEntryTime());
        batch.setQuantity(fields.getQuantity());
        batch.setInvestedCash(fields.getInvestedCash());
        batch.setRemainingCash(fields.getRemainingCash());
        batch.setPeakPrice(fields.getEntryReferencePrice());
        batch.setTroughPrice(fields.getEntryReferencePrice());
        batch.setCurrentNetReturn(BigDecimal.ZERO);
        batch.setMfe(BigDecimal.ZERO);
        batch.setMae(BigDecimal.ZERO);
        batch.setPeakDrawdown(BigDecimal.ZERO);
        batch.setFollowUntil(fields.getEntryTime() == null ? null
                : fields.getEntryTime().plusMinutes(StockNoticeComposeService.FOLLOW_MINUTES));
        batch.setFollowMaxPrice(fields.getEntryReferencePrice() == null ? null
                : fields.getEntryReferencePrice().multiply(StockNoticeComposeService.FOLLOW_PRICE_MULTIPLIER));
        batch.setBuyRuleVersion(StockRoundTransactionService.BUY_RULE_VERSION);
        batch.setSellRuleVersion(StockRoundTransactionService.SELL_RULE_VERSION);
        batch.setAllocationRuleVersion(StockRoundTransactionService.ALLOCATION_RULE_VERSION);
        batch.setMessageRuleVersion(StockRoundTransactionService.MESSAGE_RULE_VERSION);
    }
}
