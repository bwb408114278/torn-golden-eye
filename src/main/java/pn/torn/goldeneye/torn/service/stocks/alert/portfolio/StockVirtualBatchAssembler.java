package pn.torn.goldeneye.torn.service.stocks.alert.portfolio;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchEntryFields;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockRuleVersion;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeComposeService;

import java.math.BigDecimal;

/**
 * 虚拟批次字段组装器，将服务层事实转换为数据库批次字段。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.07.29
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class StockVirtualBatchAssembler {

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
        // Alpha批次已在入场/换仓阶段冻结Alpha规则身份,公共组装只补成交事实,不得覆盖为旧版默认值
        if (!StockPortfolioService.isAlphaBatch(batch)) {
            applyLegacyRuleVersions(batch);
        }
    }

    /**
     * 写入旧版正式组合的四个默认规则版本。
     * <p>
     * 只供非α批次(正式组合)使用;α批次的组合、主策略与四个规则版本由
     * {@code StockAlphaBatchIdentity}在入场/换仓阶段冻结,公共成交组装不得覆盖。
     *
     * @param batch 批次DO
     */
    private static void applyLegacyRuleVersions(TornStockVirtualBatchDO batch) {
        batch.setBuyRuleVersion(StockRuleVersion.BUY);
        batch.setSellRuleVersion(StockRuleVersion.SELL);
        batch.setAllocationRuleVersion(StockRuleVersion.ALLOCATION);
        batch.setMessageRuleVersion(StockRuleVersion.MESSAGE);
    }
}
