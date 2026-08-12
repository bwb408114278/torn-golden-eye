package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticePayloadCanonicalizer;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票通知审计写入器 - 步骤9:为已成交的买入/卖出批次写入PENDING状态的通知审计记录。
 * <p>
 * P1-1 写入职责收敛后,本类仅保留通知审计职责;信号事件、无限资金影子批次与拒绝观察批次
 * 的写入全部移交 {@link StockShadowTrackRecorder},正式/候选影子槽位批次写入移交
 * {@link StockCandidateTrackAllocationService}。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockShadowRecordWriter {

    /**
     * 通知编号前缀
     */
    private static final String NOTICE_NO_PREFIX = "N";
    /**
     * 通知编号时间戳格式
     */
    private static final String NOTICE_NO_TIMESTAMP_PATTERN = "yyyyMMddHHmmssSSS";
    /**
     * 通知编号batchId后缀取模基数
     */
    private static final int BATCH_ID_MODULUS = 1000000;
    /**
     * 通知编号格式化器
     */
    private static final DateTimeFormatter NOTICE_NO_FORMATTER =
            DateTimeFormatter.ofPattern(NOTICE_NO_TIMESTAMP_PATTERN);

    private final TornStockNoticeAuditDAO noticeAuditDao;
    private final ProjectProperty projectProperty;
    private final StockMarketClock marketClock;

    /**
     * 为已成交的买入/卖出写入PENDING通知审计。
     * <p>
     * 对每个已成交的买入批次创建BUY类型PENDING通知;
     * 对每个已成交的卖出批次创建SELL类型PENDING通知。
     *
     * @param entryFilledBatches 已成交买入批次
     * @param exitFilledBatches  已成交卖出批次
     * @param roundTime          本轮时间
     */
    public void writeNoticeAudits(List<TornStockVirtualBatchDO> entryFilledBatches,
                                  List<TornStockVirtualBatchDO> exitFilledBatches,
                                  LocalDateTime roundTime) {
        List<TornStockVirtualBatchDO> formalEntryBatches = filterFormalBatches(entryFilledBatches);
        List<TornStockVirtualBatchDO> formalExitBatches = filterFormalBatches(exitFilledBatches);
        List<TornStockNoticeAuditDO> notices = new ArrayList<>();
        collectBuyNotices(formalEntryBatches, roundTime, notices);
        collectSellNotices(formalExitBatches, roundTime, notices);

        if (!notices.isEmpty()) {
            noticeAuditDao.saveBatch(notices);
            log.info("通知审计写入完成: buyNotices={}, sellNotices={}",
                    formalEntryBatches.size(), formalExitBatches.size());
        }
    }

    /**
     * 过滤正式账本批次,避免影子成交生成正式通知。
     *
     * @param batches 待过滤批次
     * @return 正式账本批次;空值返回空列表
     */
    private List<TornStockVirtualBatchDO> filterFormalBatches(List<TornStockVirtualBatchDO> batches) {
        if (batches == null || batches.isEmpty()) {
            return List.of();
        }
        return batches.stream()
                .filter(batch -> StockLedgerTypeEnum.FORMAL.getCode().equals(batch.getLedgerType()))
                .toList();
    }

    /**
     * 为已成交买入批次构建通知审计并追加到列表。
     *
     * @param batches   已成交买入批次
     * @param roundTime 本轮时间
     * @param notices   通知审计输出列表
     */
    private void collectBuyNotices(List<TornStockVirtualBatchDO> batches,
                                   LocalDateTime roundTime,
                                   List<TornStockNoticeAuditDO> notices) {
        for (TornStockVirtualBatchDO batch : batches) {
            notices.add(buildNoticeAudit(batch, StockNoticeTypeEnum.BUY, roundTime));
        }
    }

    /**
     * 为已成交卖出批次构建通知审计并追加到列表。
     *
     * @param batches   已成交卖出批次
     * @param roundTime 本轮时间
     * @param notices   通知审计输出列表
     */
    private void collectSellNotices(List<TornStockVirtualBatchDO> batches,
                                    LocalDateTime roundTime,
                                    List<TornStockNoticeAuditDO> notices) {
        for (TornStockVirtualBatchDO batch : batches) {
            notices.add(buildNoticeAudit(batch, StockNoticeTypeEnum.SELL, roundTime));
        }
    }

    /**
     * 构建通知审计DO(PENDING状态)。
     *
     * @param batch      关联批次
     * @param noticeType 通知类型
     * @param roundTime  本轮时间
     * @return 未保存的通知审计DO
     */
    private TornStockNoticeAuditDO buildNoticeAudit(TornStockVirtualBatchDO batch,
                                                    StockNoticeTypeEnum noticeType,
                                                    LocalDateTime roundTime) {
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setNoticeNo(generateNoticeNo(batch, noticeType));
        notice.setBatchId(batch.getId());
        notice.setNoticeType(noticeType.getCode());
        notice.setGroupId(projectProperty.getVipGroupId());
        notice.setScheduledRoundTime(roundTime);
        notice.setSendStatus(StockNoticeStatusEnum.PENDING.getCode());
        notice.setSendAttemptCount(0);
        notice.setMessageRuleVersion(StockRuleVersion.MESSAGE);
        String payloadSnapshot = buildNoticePayload(batch, noticeType);
        notice.setPayloadSnapshot(payloadSnapshot);
        notice.setPayloadHash(generatePayloadHash(payloadSnapshot));
        return notice;
    }

    /**
     * 生成通知编号。
     * <p>
     * 格式: "N" + yyyyMMddHHmmssSSS + batchId后6位 + noticeType首字符
     *
     * @param batch      关联批次
     * @param noticeType 通知类型
     * @return 通知编号
     */
    private String generateNoticeNo(TornStockVirtualBatchDO batch, StockNoticeTypeEnum noticeType) {
        String timestamp = marketClock.now().format(NOTICE_NO_FORMATTER);
        String batchSuffix = batch.getId() != null
                ? String.valueOf(batch.getId() % BATCH_ID_MODULUS) : "0";
        return NOTICE_NO_PREFIX + timestamp + batchSuffix + noticeType.getCode().charAt(0);
    }

    /**
     * 生成通知载荷哈希(SHA-256:基于payload规范化JSON内容计算)。
     * <p>
     * 使用 {@link StockNoticePayloadCanonicalizer} 做确定性规范化后再计算哈希,
     * 创建、发送前合并与数据库复核共用同一canonicalizer,保证JSONB读回后哈希可复核。
     *
     * @param payloadSnapshot 载荷快照JSON文本
     * @return 载荷哈希(64位十六进制字符串)
     */
    private String generatePayloadHash(String payloadSnapshot) {
        return StockNoticePayloadCanonicalizer.sha256(payloadSnapshot);
    }

    /**
     * 构建通知载荷快照JSON(规范化)。
     * <p>
     * BUY类型包含批次、股票、入场参考价、股数、投入资金、槽位与规则版本;
     * SELL类型包含批次、股票、买卖参考价、股数、净收益、卖出收入、正式原因与规则版本。
     * 数据/管理关闭批次(ADMIN_CLOSED)额外固化 originalExitReason、adminCloseReason、
     * expectedExitBarTime、recoveryBarStart/EndTime 与 staleExitDurationSeconds,
     * 便于审计区分灾难处置与普通策略卖出。
     * 载荷通过 {@link StockNoticePayloadCanonicalizer} 规范化后落库,键序确定。
     *
     * @param batch      关联批次
     * @param noticeType 通知类型
     * @return 载荷快照JSON文本
     */
    private String buildNoticePayload(TornStockVirtualBatchDO batch, StockNoticeTypeEnum noticeType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("noticeType", noticeType.getCode());
        payload.put("batchId", batch.getId());
        payload.put("batchNo", batch.getBatchNo());
        payload.put("stocksId", batch.getStocksId());
        payload.put("stocksShortname", batch.getStocksShortname());
        payload.put("primaryStrategy", batch.getPrimaryStrategy());
        if (StockNoticeTypeEnum.BUY == noticeType) {
            payload.put("entryReferencePrice", batch.getEntryReferencePrice());
            payload.put("quantity", batch.getQuantity());
            payload.put("investedCash", batch.getInvestedCash());
            payload.put("slotNo", batch.getSlotNo());
            payload.put("buyRuleVersion", batch.getBuyRuleVersion());
            payload.put("messageRuleVersion", batch.getMessageRuleVersion());
        } else {
            payload.put("entryReferencePrice", batch.getEntryReferencePrice());
            payload.put("exitReferencePrice", batch.getExitReferencePrice());
            payload.put("quantity", batch.getQuantity());
            payload.put("netReturn", batch.getNetReturn());
            payload.put("sellProceeds", batch.getSellProceeds());
            payload.put("exitReason", batch.getExitReason());
            payload.put("exitTime", batch.getExitTime());
            payload.put("sellRuleVersion", batch.getSellRuleVersion());
            payload.put("messageRuleVersion", batch.getMessageRuleVersion());
            if (StockBatchStatusEnum.ADMIN_CLOSED.getCode().equals(batch.getBatchStatus())) {
                payload.put("formalReason", StockFormalReasonEnum.SELL_DATA_ADMIN_CLOSE.getCode());
                payload.put("originalExitReason", batch.getOriginalExitReason() != null
                        ? batch.getOriginalExitReason() : batch.getExitReason());
                payload.put("adminCloseReason", batch.getAdminCloseReason());
                payload.put("expectedExitBarTime", batch.getExpectedExitBarTime());
                payload.put("recoveryBarStartTime", batch.getRecoveryBarStartTime());
                payload.put("recoveryBarEndTime", batch.getRecoveryBarEndTime());
                payload.put("staleExitDurationSeconds", batch.getStaleExitDurationSeconds());
            } else {
                payload.put("formalReason", resolveFormalReason(batch.getExitReason()));
            }
        }
        return StockNoticePayloadCanonicalizer.canonicalize(JsonUtils.objToJson(payload));
    }

    /**
     * 将原策略退出类型映射为正式卖出原因编码。
     * <p>
     * 普通策略卖出(非数据/管理关闭)使用稳定正式原因码;未知编码回退为原退出类型本身,
     * 不在此处抛异常,避免通知审计写入被未知编码阻塞。
     *
     * @param exitReason 原策略退出类型编码
     * @return 正式卖出原因编码
     */
    private String resolveFormalReason(String exitReason) {
        if (exitReason == null) {
            return null;
        }
        return switch (exitReason) {
            case "CLOSED_TARGET" -> StockFormalReasonEnum.SELL_TARGET_REACHED.getCode();
            case "CLOSED_RANGE" -> StockFormalReasonEnum.SELL_RANGE_RECOVERED.getCode();
            case "CLOSED_RISK" -> StockFormalReasonEnum.SELL_HARD_RISK.getCode();
            case "CLOSED_TIME" -> StockFormalReasonEnum.SELL_MAX_HOLD.getCode();
            default -> exitReason;
        };
    }
}
