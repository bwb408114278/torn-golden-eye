package pn.torn.goldeneye.torn.service.stocks.alert.shadow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.policy.CandidateInfo;
import pn.torn.goldeneye.utils.JsonUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockVirtualBatchAssembler;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockBuySignalEvaluator;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockBuySignalResult;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockCandidateAllocationResult;

/**
 * 股票候选轨道接纳服务 - 在短事务内将候选接纳到正式或候选影子槽位账本。
 * <p>
 * 收敛 P1-1 写入职责: 正式与候选影子两种有槽位账本的候选接纳写入只在本类发生,
 * 信号事件、无限资金影子与拒绝观察批次由 {@link StockShadowTrackRecorder} 负责,
 * 本类不包含通知审计逻辑。从 {@link StockBuySignalEvaluator} 拆出,使评估器保持纯规则。
 * <p>
 * 候选影子与正式共享同股单活跃规则: 二者均为槽位账本,同股不得重复建立槽位批次。
 * 排序结果由调用方保证唯一(每轮只排序一次),不同目标轨道共用同一排序,不得因轨道不同
 * 产生不同成交价格或时间边界。
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.09
 */
@Slf4j
@Component
public class StockCandidateTrackAllocationService {

    /**
     * 批次编号时间戳格式(正式与候选影子共用)
     */
    private static final String FORMAL_BATCH_NO_TIMESTAMP_PATTERN = "yyyyMMddHHmm";
    /**
     * 批次编号格式化器(正式与候选影子共用)
     */
    private static final DateTimeFormatter FORMAL_BATCH_NO_FORMATTER =
            DateTimeFormatter.ofPattern(FORMAL_BATCH_NO_TIMESTAMP_PATTERN);

    private final TornStockVirtualBatchDAO virtualBatchDao;
    private final StockPortfolioService portfolioService;
    private final StockShadowTrackRecorder shadowTrackRecorder;

    /**
     * 构造候选轨道接纳服务。
     *
     * @param virtualBatchDao     虚拟批次持久层
     * @param portfolioService    槽位资金领域服务
     * @param shadowTrackRecorder 影子轨道记录器(信号事件与无限资金影子批次写入)
     */
    public StockCandidateTrackAllocationService(TornStockVirtualBatchDAO virtualBatchDao,
                                                StockPortfolioService portfolioService,
                                                StockShadowTrackRecorder shadowTrackRecorder) {
        this.virtualBatchDao = virtualBatchDao;
        this.portfolioService = portfolioService;
        this.shadowTrackRecorder = shadowTrackRecorder;
    }

    /**
     * 按排序结果接纳候选,检查目标组合可用槽位并预留,返回新建批次列表。
     * <p>
     * 遍历排序后的候选列表,对每个候选:
     * <ol>
     *   <li>在目标组合内查找可用槽位(findFirstAvailableFromSnapshot);无槽位时记录容量事实</li>
     *   <li>计算股数(calculateQuantity);股数不足时跳过该候选</li>
     *   <li>按目标账本创建批次(ENTRY_PENDING),预留槽位,并回填事件批次ID</li>
     * </ol>
     * 单个候选的接纳逻辑提取为 {@link #acceptSingleCandidate},返回 null 表示该候选被跳过。
     *
     * @param rankedCandidates    排序后的候选列表
     * @param snapshot            轮次快照
     * @param barByStock          按股票ID索引的bar映射
     * @param monthlyStateByStock 按股票ID索引的月度状态映射
     * @param evaluationByStockId 按股票ID索引的信号评估映射
     * @param roundTime           本轮时间
     * @param target              候选接纳目标(组合编码+账本类型+批次编号前缀)
     * @return 新建批次与每个候选的实际接纳结果
     */
    public StockCandidateAllocationResult acceptCandidates(
            List<CandidateInfo> rankedCandidates,
            RoundSnapshot snapshot,
            Map<Integer, TornStockMarketBar15mDO> barByStock,
            Map<Integer, TornStockMonthlyStateDO> monthlyStateByStock,
            Map<Integer, StockBuySignalResult.SignalEvaluation> evaluationByStockId,
            LocalDateTime roundTime,
            CandidateAcceptanceTarget target) {
        Objects.requireNonNull(roundTime, "轮次时间不能为空");
        Objects.requireNonNull(target, "候选接纳目标不能为空");
        List<TornStockVirtualBatchDO> newBatches = new ArrayList<>();
        Map<Integer, StockCandidateAllocationResultEnum> resultByStockId = new LinkedHashMap<>();
        if (rankedCandidates == null || rankedCandidates.isEmpty()) {
            return StockCandidateAllocationResult.empty();
        }

        int candidateRank = 0;
        for (CandidateInfo candidate : rankedCandidates) {
            candidateRank++;
            Optional<TornStockPortfolioSlotDO> slotOpt = findFirstAvailableFromSnapshot(snapshot, target.portfolioCode());
            if (slotOpt.isEmpty()) {
                log.debug("无可用槽位,拒绝接纳候选: stocksId={}, rank={}, portfolioCode={}",
                        candidate.stocksId(), candidateRank, target.portfolioCode());
                resultByStockId.put(candidate.stocksId(), StockCandidateAllocationResultEnum.NO_AVAILABLE_SLOT);
                continue;
            }

            CandidateAcceptance acceptance = acceptSingleCandidate(
                    new AcceptanceInput(candidate, slotOpt.get(), barByStock.get(candidate.stocksId()),
                            monthlyStateByStock.get(candidate.stocksId()),
                            evaluationByStockId.get(candidate.stocksId())),
                    candidateRank, roundTime, target);
            resultByStockId.put(candidate.stocksId(), acceptance.result());
            if (acceptance.batch() != null) {
                newBatches.add(acceptance.batch());
            }
        }
        return new StockCandidateAllocationResult(newBatches, resultByStockId);
    }

    /**
     * 从内存快照中查找目标组合首个可用槽位,避免数据库查询。
     *
     * @param snapshot      轮次快照
     * @param portfolioCode 目标组合编码
     * @return 首个AVAILABLE槽位;无则返回empty
     */
    private Optional<TornStockPortfolioSlotDO> findFirstAvailableFromSnapshot(RoundSnapshot snapshot,
                                                                              String portfolioCode) {
        return snapshot.slots().stream()
                .filter(slot -> portfolioCode.equals(slot.getPortfolioCode()))
                .filter(slot -> StockSlotStatusEnum.AVAILABLE.getCode().equals(slot.getSlotStatus()))
                .findFirst();
    }

    /**
     * 接纳单个候选:校验 bar 与股数,创建目标账本批次并预留槽位。
     * <p>
     * 以下情况返回 {@link CandidateAcceptance#batch()} 为null:
     * <ul>
     *   <li>bar 缺失或价格无效</li>
     *   <li>可用资金不足买入1股</li>
     * </ul>
     *
     * @param input         候选接纳输入(候选、槽位、bar、月度状态与信号评估)
     * @param candidateRank 候选排名(1起始)
     * @param roundTime     本轮时间
     * @param target        候选接纳目标
     * @return 单个候选的批次与接纳结果
     */
    private CandidateAcceptance acceptSingleCandidate(
            AcceptanceInput input,
            int candidateRank,
            LocalDateTime roundTime,
            CandidateAcceptanceTarget target) {
        CandidateInfo candidate = input.candidate();
        TornStockPortfolioSlotDO slot = input.slot();
        TornStockMarketBar15mDO bar = input.bar();
        if (bar == null || bar.getLastPrice() == null || bar.getLastPrice().signum() <= 0) {
            log.warn("候选[{}]本轮bar无效,跳过", candidate.stocksId());
            return new CandidateAcceptance(null, StockCandidateAllocationResultEnum.DATA_NOT_READY);
        }

        BigDecimal signalReferencePrice = bar.getLastPrice();
        BigDecimal reservedAmount = slot.getAvailableCash();
        Long quantity = StockPortfolioService.calculateQuantity(reservedAmount, signalReferencePrice);
        if (quantity <= 0) {
            log.debug("候选[{}]可用资金不足买入1股,跳过: availableCash={}, price={}",
                    candidate.stocksId(), reservedAmount, signalReferencePrice);
            return new CandidateAcceptance(null, StockCandidateAllocationResultEnum.INSUFFICIENT_FUNDS);
        }

        FormalBatchContext ctx = new FormalBatchContext(
                candidate, slot, input.monthlyState(), signalReferencePrice, quantity, roundTime);
        TornStockSignalEventDO event = shadowTrackRecorder.recordTrackSignalEvent(
                input.evaluation(), candidateRank, roundTime, target.eventDecision());
        TornStockVirtualBatchDO existingBatch = virtualBatchDao.selectBySignalEventIdAndLedgerTypeForUpdate(
                event.getId(), target.ledgerType());
        if (existingBatch != null) {
            return new CandidateAcceptance(existingBatch, target.allocatedResult());
        }
        TornStockVirtualBatchDO batch = createTrackBatch(ctx, target);
        batch.setSignalEventId(event.getId());
        batch.setBatchNo(target.batchNoPrefix() + event.getId());

        virtualBatchDao.insertIgnoreConflict(batch);
        TornStockVirtualBatchDO persistedBatch = virtualBatchDao.selectBySignalEventIdAndLedgerTypeForUpdate(
                event.getId(), target.ledgerType());
        if (persistedBatch == null || persistedBatch.getId() == null) {
            throw new IllegalStateException("候选批次插入后无法读取: signalEventId=" + event.getId()
                    + ", ledgerType=" + target.ledgerType());
        }
        batch = persistedBatch;
        if (StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode().equals(target.ledgerType())) {
            // 候选影子批次: 回填事件shadowCandidateBatchId并创建无限资金影子孪生批次
            shadowTrackRecorder.linkCandidateShadowEvent(event, batch);
        } else {
            target.applyEventBatchId(event, batch.getId());
            shadowTrackRecorder.updateSignalEventBatchIds(event);
        }

        portfolioService.reserveSlot(slot, reservedAmount, batch.getId());

        log.info("候选接纳: stocksId={}, slotNo={}, signalPrice={}, quantity={}, reserved={}, batchId={}, ledgerType={}",
                candidate.stocksId(), slot.getSlotNo(),
                signalReferencePrice, quantity, reservedAmount, batch.getId(), target.ledgerType());
        return new CandidateAcceptance(batch, target.allocatedResult());
    }

    /**
     * 创建目标账本批次DO(ENTRY_PENDING状态)。
     * <p>
     * 使用 {@link FormalBatchContext} 封装参数,避免超过 Sonar 方法参数上限(7)。
     *
     * @param ctx    批次构建上下文
     * @param target 候选接纳目标
     * @return 未保存的目标账本批次DO
     */
    private TornStockVirtualBatchDO createTrackBatch(FormalBatchContext ctx, CandidateAcceptanceTarget target) {
        CandidateInfo candidate = ctx.candidate();
        TornStockPortfolioSlotDO slot = ctx.slot();
        LocalDateTime roundTime = ctx.roundTime();

        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setBatchNo(target.batchNoPrefix()
                + roundTime.format(FORMAL_BATCH_NO_FORMATTER) + candidate.stocksId());
        batch.setLedgerType(target.ledgerType());
        batch.setStocksId(candidate.stocksId());
        batch.setStocksShortname(candidate.stocksShortname());
        batch.setPrimaryStrategy(candidate.primaryStrategy().getCode());
        batch.setMatchedStrategies(JsonUtils.objToJson(candidate.matchedStrategies()));
        batch.setQualityScore(candidate.qualityScore());
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        // signalEventId在信号事件保存后回填;目标账本批次尚未进入持久化批量写入。
        batch.setSlotId(slot.getId());
        batch.setSlotNo(slot.getSlotNo());
        batch.setSignalTime(roundTime);
        batch.setQuantity(ctx.quantity());
        TornStockVirtualBatchSignalFields fields = StockVirtualBatchAssembler.buildSignalFields(
                ctx.signalReferencePrice(), roundTime, ctx.monthlyState());
        StockVirtualBatchAssembler.applySignalFields(batch, fields);
        return batch;
    }

    /**
     * 候选接纳目标 - 定义候选被接纳到哪个组合、以什么账本类型与批次编号前缀创建。
     * <p>
     * 仅支持本文定义的正式组合与候选影子组合两种有槽位组合,不建立通用多组合框架。
     *
     * @param portfolioCode   目标组合编码(VIP_FORMAL或VIP_SHADOW_CANDIDATE)
     * @param ledgerType      目标账本类型编码(FORMAL或SHADOW_FORMAL_CANDIDATE)
     * @param batchNoPrefix   批次编号前缀(F或C)
     * @param eventDecision   事件组合决策编码(FORMAL或SHADOW)
     * @param allocatedResult 分配成功的结果枚举(FORMAL_ALLOCATED或SHADOW_CANDIDATE_ALLOCATED)
     */
    public record CandidateAcceptanceTarget(
            String portfolioCode,
            String ledgerType,
            String batchNoPrefix,
            String eventDecision,
            StockCandidateAllocationResultEnum allocatedResult) {

        /**
         * 正式组合接纳目标。
         *
         * @return 正式目标
         */
        public static CandidateAcceptanceTarget formal() {
            return new CandidateAcceptanceTarget(
                    StockPortfolioService.PORTFOLIO_CODE,
                    StockLedgerTypeEnum.FORMAL.getCode(),
                    "F",
                    StockPortfolioDecisionEnum.FORMAL.getCode(),
                    StockCandidateAllocationResultEnum.FORMAL_ALLOCATED);
        }

        /**
         * 候选影子组合接纳目标。
         *
         * @return 候选影子目标
         */
        public static CandidateAcceptanceTarget candidateShadow() {
            return new CandidateAcceptanceTarget(
                    StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE,
                    StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode(),
                    "C",
                    StockPortfolioDecisionEnum.SHADOW.getCode(),
                    StockCandidateAllocationResultEnum.SHADOW_CANDIDATE_ALLOCATED);
        }

        /**
         * 将新建批次ID回填到信号事件对应字段。
         * <p>
         * 正式目标回填formalBatchId,候选影子目标回填shadowCandidateBatchId。
         *
         * @param event   信号事件
         * @param batchId 新建批次ID
         */
        public void applyEventBatchId(TornStockSignalEventDO event, Long batchId) {
            if (StockLedgerTypeEnum.FORMAL.getCode().equals(ledgerType)) {
                event.setFormalBatchId(batchId);
            } else if (StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode().equals(ledgerType)) {
                event.setShadowCandidateBatchId(batchId);
            }
        }
    }

    /**
     * 单个候选的正式接纳结果。
     *
     * @param batch  已创建正式批次，失败时为空
     * @param result 实际接纳结果
     */
    private record CandidateAcceptance(
            TornStockVirtualBatchDO batch,
            StockCandidateAllocationResultEnum result
    ) {
    }

    /**
     * 候选接纳输入 - 封装单个候选接纳所需的候选、槽位、bar、月度状态与信号评估事实。
     *
     * @param candidate    候选信息
     * @param slot         已分配的可用槽位
     * @param bar          该候选股票本轮bar
     * @param monthlyState 该候选股票的月度状态
     * @param evaluation   该候选对应的完整信号评估事实
     */
    private record AcceptanceInput(
            CandidateInfo candidate,
            TornStockPortfolioSlotDO slot,
            TornStockMarketBar15mDO bar,
            TornStockMonthlyStateDO monthlyState,
            StockBuySignalResult.SignalEvaluation evaluation
    ) {
    }

    /**
     * 目标账本批次构建上下文 - 封装 {@link #createTrackBatch} 的参数,避免超过 Sonar 方法参数上限。
     *
     * @param candidate            候选信息
     * @param slot                 分配的槽位
     * @param monthlyState         月度状态(用于填充风格/风险字段)
     * @param signalReferencePrice 信号参考价
     * @param quantity             计划买入股数
     * @param roundTime            本轮时间
     */
    private record FormalBatchContext(
            CandidateInfo candidate,
            TornStockPortfolioSlotDO slot,
            TornStockMonthlyStateDO monthlyState,
            BigDecimal signalReferencePrice,
            Long quantity,
            LocalDateTime roundTime
    ) {
    }
}
