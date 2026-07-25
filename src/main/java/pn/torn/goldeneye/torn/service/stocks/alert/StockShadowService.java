package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.utils.JsonUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 股票影子账本服务 - 维护原始信号事件、无限资金影子批次与拒绝观察批次
 * <p>
 * 本服务承担三类不产生即时群消息的账本维护职责,作为信号回测、策略迭代与
 * 拒绝机会跟踪的数据底座,与正式组合({@link StockPortfolioService})解耦:
 *
 * <h3>原始信号事件账本</h3>
 * <p>
 * 记录所有 false -&gt; true 信号事件,不受资金和槽位限制。保存信号时间、股票与策略、
 * 特征快照、风格快照、资格结果与原因、候选排名与组合决策,以及后续 MFE/MAE 与
 * 理论结果,作为信号回测与策略迭代的核心数据。不发送即时群消息。
 *
 * <h3>无限资金影子批次</h3>
 * <p>
 * 不受正式5槽限制,同一股票×策略版本最多一个开放影子批次,完整模拟买入到卖出,
 * 用于判断信号本身是否有优势。不发送即时群消息。
 *
 * <h3>拒绝观察批次</h3>
 * <p>
 * 跟踪因风格、风险观察、趋势保护、同股、冷却、未复位、满仓、数据不连续或价格偏离
 * 被拒绝的机会。不占正式槽位、不发正式买入、可继续跟踪理论路径、不产生需要群消息
 * 关闭的正式卖出。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockShadowService {

    /**
     * 事件编号前缀
     */
    private static final String EVENT_NO_PREFIX = "E";
    /**
     * 无限资金影子批次编号前缀
     */
    private static final String SHADOW_BATCH_NO_PREFIX = "S";
    /**
     * 拒绝观察批次编号前缀
     */
    private static final String REJECTED_BATCH_NO_PREFIX = "R";
    /**
     * 事件/批次编号时间戳格式(yyyyMMddHHmm)
     */
    private static final String NO_TIMESTAMP_PATTERN = "yyyyMMddHHmm";
    /**
     * 策略类型截取长度(取前3字符用于事件编号)
     */
    private static final int STRATEGY_TYPE_TRUNCATE_LENGTH = 3;
    /**
     * 编号时间戳格式化器
     */
    private static final DateTimeFormatter NO_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern(NO_TIMESTAMP_PATTERN);

    private final TornStockSignalEventDAO signalEventDao;
    private final TornStockVirtualBatchDAO virtualBatchDao;

    // ==================== 原始信号事件 ====================

    /**
     * 记录原始信号事件并保存。
     * <p>
     * 创建并保存一次 false -&gt; true 信号事件的完整快照,生成业务唯一事件编号
     * (格式: "E" + yyyyMMddHHmm + stocksId + strategyType前3字符),
     * 写入特征快照、风格快照、资格结果与原因、候选排名与组合决策等字段。
     * 不发送即时群消息。
     *
     * @param context 信号事件上下文,包含创建事件所需的全部信息
     * @return 已保存的信号事件DO(含主键ID与事件编号)
     */
    public TornStockSignalEventDO recordSignalEvent(StockSignalEventContext context) {
        Objects.requireNonNull(context, "信号事件上下文不能为空");
        Objects.requireNonNull(context.stocksId(), "股票ID不能为空");
        Objects.requireNonNull(context.strategyType(), "策略类型不能为空");

        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setEventNo(generateEventNo(context.stocksId(), context.strategyType()));
        event.setRoundTime(context.roundTime());
        event.setStocksId(context.stocksId());
        event.setStocksShortname(context.stocksShortname());
        event.setStrategyType(context.strategyType());
        event.setBuyRuleVersion(context.buyRuleVersion());
        event.setQualityScore(context.qualityScore());
        event.setFeatureSnapshot(context.featureSnapshot());
        event.setStyleSnapshot(context.styleSnapshot());
        event.setEligibilityResult(context.eligibilityResult());
        event.setEligibilityReasons(convertReasonsToJson(context.eligibilityReasons()));
        event.setCandidateRank(context.candidateRank());
        event.setPortfolioDecision(context.portfolioDecision());
        event.setRejectReason(context.rejectReason());

        signalEventDao.save(event);
        log.info("信号事件记录-完成: eventNo={}, stocksId={}, strategy={}, decision={}",
                event.getEventNo(), event.getStocksId(), event.getStrategyType(), event.getPortfolioDecision());
        return event;
    }

    // ==================== 无限资金影子批次 ====================

    /**
     * 创建无限资金影子批次。
     * <p>
     * 为指定信号事件创建无限资金影子批次(ledgerType = UNLIMITED_SHADOW),
     * 批次状态初始为 ENTRY_PENDING,批次编号格式为 "S" + yyyyMMddHHmm + stocksId。
     * 不分配正式槽位(slotId/slotNo 为 null),不受正式5槽限制。不发送即时群消息。
     *
     * @param event 关联的信号事件(须已保存,含主键ID)
     * @return 已保存的影子批次DO(含主键ID与批次编号)
     */
    public TornStockVirtualBatchDO createUnlimitedShadowBatch(TornStockSignalEventDO event) {
        Objects.requireNonNull(event, "信号事件不能为空");
        Objects.requireNonNull(event.getId(), "信号事件主键ID不能为空");

        TornStockVirtualBatchDO batch = buildBaseBatch(event);
        batch.setBatchNo(generateBatchNo(SHADOW_BATCH_NO_PREFIX, event.getStocksId()));
        batch.setLedgerType(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());

        virtualBatchDao.save(batch);
        log.info("无限资金影子批次创建-完成: batchNo={}, stocksId={}, signalEventId={}",
                batch.getBatchNo(), batch.getStocksId(), event.getId());
        return batch;
    }

    // ==================== 拒绝观察批次 ====================

    /**
     * 创建拒绝观察批次。
     * <p>
     * 为被拒绝的信号事件创建拒绝观察批次(ledgerType = REJECTED_OBSERVATION),
     * 批次状态直接置为 CANCELLED(拒绝观察只跟踪理论路径,不进入买入流程),
     * 批次编号格式为 "R" + yyyyMMddHHmm + stocksId。不占正式槽位、不发正式买入。
     *
     * @param event        关联的信号事件(须已保存,含主键ID)
     * @param rejectReason 拒绝原因编码
     * @return 已保存的拒绝观察批次DO(含主键ID与批次编号)
     */
    public TornStockVirtualBatchDO createRejectedObservationBatch(TornStockSignalEventDO event, String rejectReason) {
        Objects.requireNonNull(event, "信号事件不能为空");
        Objects.requireNonNull(event.getId(), "信号事件主键ID不能为空");

        TornStockVirtualBatchDO batch = buildBaseBatch(event);
        batch.setBatchNo(generateBatchNo(REJECTED_BATCH_NO_PREFIX, event.getStocksId()));
        batch.setLedgerType(StockLedgerTypeEnum.REJECTED_OBSERVATION.getCode());
        batch.setBatchStatus(StockBatchStatusEnum.CANCELLED.getCode());
        batch.setCancelReason(rejectReason);

        virtualBatchDao.save(batch);
        log.info("拒绝观察批次创建-完成: batchNo={}, stocksId={}, signalEventId={}, rejectReason={}",
                batch.getBatchNo(), batch.getStocksId(), event.getId(), rejectReason);
        return batch;
    }

    // ==================== 查询 ====================

    /**
     * 检查同一股票×策略是否已有开放的无限资金影子批次。
     * <p>
     * 查询 ledgerType = UNLIMITED_SHADOW 且 stocksId 等于指定股票ID 且 primaryStrategy
     * 等于指定策略 且 batchStatus 为活跃状态(ENTRY_PENDING/OPEN/DATA_STALE/
     * EXIT_PENDING/DATA_STALE_EXIT)的记录是否存在。用于保证同一股票×策略版本
     * 最多一个开放影子批次。
     *
     * @param stocksId      股票ID
     * @param strategyType 策略类型编码
     * @return 已存在开放影子批次时返回true,否则返回false
     */
    public boolean hasOpenShadowBatch(Integer stocksId, String strategyType) {
        Objects.requireNonNull(stocksId, "股票ID不能为空");
        Objects.requireNonNull(strategyType, "策略类型不能为空");

        Long count = virtualBatchDao.lambdaQuery()
                .eq(TornStockVirtualBatchDO::getLedgerType, StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode())
                .eq(TornStockVirtualBatchDO::getStocksId, stocksId)
                .eq(TornStockVirtualBatchDO::getPrimaryStrategy, strategyType)
                .in(TornStockVirtualBatchDO::getBatchStatus,
                        StockBatchStatusEnum.ENTRY_PENDING.getCode(),
                        StockBatchStatusEnum.OPEN.getCode(),
                        StockBatchStatusEnum.DATA_STALE.getCode(),
                        StockBatchStatusEnum.EXIT_PENDING.getCode(),
                        StockBatchStatusEnum.DATA_STALE_EXIT.getCode())
                .count();
        return count != null && count > 0;
    }

    // ==================== 辅助方法 ====================

    /**
     * 生成事件编号。
     * <p>
     * 格式: "E" + yyyyMMddHHmm + stocksId + strategyType前3字符。
     * 时间戳取当前时间,保证同一分钟内同股同策略的事件编号唯一。
     *
     * @param stocksId     股票ID
     * @param strategyType 策略类型编码
     * @return 事件编号
     */
    private String generateEventNo(Integer stocksId, String strategyType) {
        String timestamp = LocalDateTime.now().format(NO_TIMESTAMP_FORMATTER);
        String strategySuffix = truncateStrategyType(strategyType);
        return EVENT_NO_PREFIX + timestamp + stocksId + strategySuffix;
    }

    /**
     * 生成批次编号。
     * <p>
     * 格式: prefix + yyyyMMddHHmm + stocksId。
     * 时间戳取当前时间,保证同一分钟内同股同前缀的批次编号唯一。
     *
     * @param prefix   批次编号前缀("S"或"R")
     * @param stocksId 股票ID
     * @return 批次编号
     */
    private String generateBatchNo(String prefix, Integer stocksId) {
        String timestamp = LocalDateTime.now().format(NO_TIMESTAMP_FORMATTER);
        return prefix + timestamp + stocksId;
    }

    /**
     * 截取策略类型编码前3字符用于事件编号后缀。
     * <p>
     * 策略类型不足3字符时取全部字符。
     *
     * @param strategyType 策略类型编码
     * @return 截取后的策略后缀
     */
    private String truncateStrategyType(String strategyType) {
        if (strategyType.length() <= STRATEGY_TYPE_TRUNCATE_LENGTH) {
            return strategyType;
        }
        return strategyType.substring(0, STRATEGY_TYPE_TRUNCATE_LENGTH);
    }

    /**
     * 将资格审查原因列表转换为JSON文本。
     * <p>
     * 原因列表为空或null时返回空数组JSON "[]"。使用项目统一的
     * {@link JsonUtils} 保证与项目其他JSON序列化口径一致。
     *
     * @param reasons 原因编码列表
     * @return JSON文本
     */
    private String convertReasonsToJson(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "[]";
        }
        return JsonUtils.objToJson(reasons);
    }

    /**
     * 基于信号事件构建批次基础字段(不含批次编号、账本类型、批次状态)。
     * <p>
     * 复用股票ID、简称、主策略、质量评分、信号事件ID、信号时间等公共字段填充逻辑,
     * 影子批次与拒绝观察批次共用此基础构建。slotId/slotNo 保持为 null(不占正式槽位)。
     *
     * @param event 关联的信号事件
     * @return 已填充基础字段的批次DO
     */
    private TornStockVirtualBatchDO buildBaseBatch(TornStockSignalEventDO event) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setStocksId(event.getStocksId());
        batch.setStocksShortname(event.getStocksShortname());
        batch.setPrimaryStrategy(event.getStrategyType());
        batch.setQualityScore(event.getQualityScore());
        batch.setSignalEventId(event.getId());
        batch.setSignalTime(event.getRoundTime());
        batch.setBuyRuleVersion(event.getBuyRuleVersion());
        // slotId/slotNo 保持 null: 影子与拒绝观察批次不占正式槽位
        return batch;
    }

    /**
     * 信号事件上下文 - 封装创建原始信号事件所需的全部信息。
     * <p>
     * 作为 {@link #recordSignalEvent(StockSignalEventContext)} 的入参,
     * 由调用方在策略匹配与资格评估完成后组装,保证事件记录的字段完整性。
     *
     * @param stocksId          股票ID
     * @param stocksShortname   股票简称快照
     * @param strategyType      策略类型编码
     * @param buyRuleVersion    买入规则版本
     * @param qualityScore      信号质量评分
     * @param featureSnapshot   特征快照(JSON文本)
     * @param styleSnapshot     风格快照(JSON文本)
     * @param eligibilityResult 资格审查结果编码(ALLOWED/REJECTED/OBSERVED)
     * @param eligibilityReasons 资格审查原因编码列表,可为null
     * @param candidateRank     候选排名,未通过资格审查时为null
     * @param portfolioDecision 组合决策编码(FORMAL/SHADOW/REJECTED)
     * @param rejectReason      拒绝原因编码,portfolioDecision为REJECTED时非空,可为null
     * @param roundTime         信号产生的轮次时间
     * @author Bai
     * @version 1.2.12
     * @since 2026.07.25
     */
    public record StockSignalEventContext(
            Integer stocksId,
            String stocksShortname,
            String strategyType,
            String buyRuleVersion,
            BigDecimal qualityScore,
            String featureSnapshot,
            String styleSnapshot,
            String eligibilityResult,
            List<String> eligibilityReasons,
            Integer candidateRank,
            String portfolioDecision,
            String rejectReason,
            LocalDateTime roundTime
    ) {
    }
}
