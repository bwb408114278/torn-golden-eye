package pn.torn.goldeneye.torn.service.stocks.alert.settlement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockAnnualSettlementStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.*;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.*;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockHashUtils;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.PortfolioEquityCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.settlement.StockAnnualSettlementCalculator.SettlementInput;
import pn.torn.goldeneye.torn.service.stocks.alert.settlement.StockAnnualSettlementCalculator.SettlementResult;
import pn.torn.goldeneye.torn.service.stocks.alert.settlement.StockAnnualSettlementNoticeService.AnnualSettlementNoticePayload;
import pn.torn.goldeneye.torn.service.stocks.alert.settlement.StockAnnualSettlementRenderer.AnnualReportData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * α正式仓年度结算服务 - 年度边界权益与"累计提取利润"科目的唯一编排入口。
 *
 * <p>结算口径固定为方案C:只读既有资金与持仓事实,追加式台账,不强制平仓、不发生任何资金划转;
 * 全程禁止写入 {@code torn_stock_portfolio_slot}、{@code torn_stock_virtual_batch} 与行情表。
 *
 * <p>流程:
 * <ol>
 *   <li>幂等短路:同一 {@code (portfolio_code, settle_year, rule_version)} 已SETTLED或处于终态时直接返回;</li>
 *   <li>可证窗口:业务时间必须落在边界时点起15分钟内,出窗落MANUAL_REVIEW且不自动补结;</li>
 *   <li>Gate A 边界桶轮次必须COMPLETED;Gate B 边界之后无新α资金事实且边界轮次完成后槽位未被再写入;
 *       Gate C 边界行情齐备且权益可算(缺失行情时绝不伪造结算);</li>
 *   <li>累计提取连续性:上一年度未结算时fail-closed为BLOCKED_PRIOR_YEAR,不允许跳年;</li>
 *   <li>单一短事务内落台账金额并创建年度报告PENDING通知行;领取、冻结与发送由调用方在事务提交后执行。</li>
 * </ol>
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAnnualSettlementService {
    /**
     * 结算口径版本
     */
    public static final String RULE_VERSION = "1.0.0";
    /**
     * 降级原因最大长度(与台账列宽一致)
     */
    private static final int MAX_DEGRADE_REASON_LENGTH = 512;
    /**
     * 降级原因截断后缀
     */
    private static final String TRUNCATED_SUFFIX = "...";
    /**
     * 可证窗口长度(分钟):边界时点起,至新年度第一桶轮次开始写入槽位之前。
     */
    private static final long PROVABLE_WINDOW_MINUTES = Stock15mBarBuildService.BUCKET_MINUTES;

    private final TornStockPortfolioAnnualSettlementDAO settlementDAO;
    private final TornStockPortfolioSlotDAO slotDAO;
    private final TornStockVirtualBatchDAO batchDAO;
    private final TornStockMarketRoundDAO roundDAO;
    private final TornStockMarketBar15mDAO barDAO;
    private final PortfolioEquityCalculator equityCalculator;
    private final StockAnnualSettlementCalculator calculator;
    private final StockAnnualSettlementRenderer renderer;
    private final StockAnnualSettlementNoticeService noticeService;

    /**
     * 结算指定自然年并产出待投递的年度报告。
     * <p>
     * 全部门禁与写入在同一短事务内完成:只写本公司的新台账行与年度报告PENDING通知行,
     * 任何UPDATE都不指向槽位、批次与行情表。返回的通知必须由调用方在事务提交后领取发送。
     *
     * @param settleYear  被结算的自然年
     * @param businessNow 业务时间
     * @return 待投递的年度报告;幂等短路、终态跳过、并发落库失败或门禁未通过时返回null
     */
    @Transactional
    public AnnualSettlementOutcome settle(int settleYear, LocalDateTime businessNow) {
        String portfolioCode = StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE;
        LocalDateTime boundaryTime = LocalDateTime.of(settleYear + 1, 1, 1, 0, 0);
        LocalDateTime boundaryBarStart = boundaryTime.minusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
        SettlementTarget target = new SettlementTarget(portfolioCode, settleYear, boundaryTime, boundaryBarStart,
                businessNow);

        TornStockPortfolioAnnualSettlementDO existing = settlementDAO.selectByBusinessKey(
                portfolioCode, settleYear, RULE_VERSION);
        if (isFinished(target, existing)) {
            return null;
        }
        LocalDateTime windowEnd = boundaryTime.plusMinutes(PROVABLE_WINDOW_MINUTES);
        if (businessNow.isBefore(boundaryTime) || !businessNow.isBefore(windowEnd)) {
            degrade(target, existing, StockAnnualSettlementStatusEnum.MANUAL_REVIEW,
                    "已离开年度结算可证窗口: businessNow=" + businessNow
                            + ", window=[" + boundaryTime + "," + windowEnd + ")");
            return null;
        }

        List<TornStockPortfolioSlotDO> slots = slotDAO.selectAllByPortfolioCode(portfolioCode);
        if (slots.isEmpty()) {
            degrade(target, existing, StockAnnualSettlementStatusEnum.MANUAL_REVIEW, "正式仓槽位不存在,无法结算");
            return null;
        }
        TornStockMarketRoundDO boundaryRound = requireCompletedBoundaryRound(target, existing);
        if (boundaryRound == null) {
            return null;
        }
        if (!passNoPostBoundaryChangeGate(target, existing, slots, boundaryRound)) {
            return null;
        }

        List<TornStockVirtualBatchDO> activeBatches = batchDAO.selectActiveAlphaBatches(portfolioCode);
        List<TornStockVirtualBatchDO> openPositions = equityCalculator.extractOpenPositionBatches(activeBatches);
        Map<Integer, TornStockMarketBar15mDO> boundaryBars = loadBoundaryBars(boundaryBarStart, openPositions);
        PortfolioEquityCalculator.EquityResult equityResult = equityCalculator.calculateEquity(
                slots, activeBatches, boundaryBars, boundaryTime);
        if (equityResult.equity() == null) {
            degrade(target, existing, StockAnnualSettlementStatusEnum.DEGRADED_PRICE_MISSING,
                    "边界行情缺失: " + String.join(",", equityResult.missingPriceStocks()));
            return null;
        }

        LocalDate firstEntryDate = resolveFirstEntryDate(portfolioCode, slots);
        if (firstEntryDate == null || !firstEntryDate.isBefore(boundaryTime.toLocalDate())) {
            degrade(target, existing, StockAnnualSettlementStatusEnum.MANUAL_REVIEW,
                    "年度结算首笔入场日非法: firstEntryDate=" + firstEntryDate);
            return null;
        }
        BigDecimal cumulativeBefore = resolveCumulativeBefore(target, existing, firstEntryDate.getYear());
        if (cumulativeBefore == null) {
            return null;
        }

        SettlementResult result = calculator.calculate(new SettlementInput(StockPortfolioService.VIP_ALPHA_INITIAL_CASH,
                cumulativeBefore, equityResult.equity(), sumAvailableCash(slots), sumReservedCash(slots),
                firstEntryDate, settleYear, boundaryTime));
        TornStockPortfolioAnnualSettlementDO computed = buildSettledRow(target, result, equityResult,
                openPositions.size(), boundaryBarStart, boundaryBars, cumulativeBefore);
        computed.setClosingCash(sumAvailableCash(slots));
        computed.setClosingReserved(sumReservedCash(slots));
        return persistSettled(target, computed, collectOpenPositionStocks(openPositions));
    }

    /**
     * 判断该年度是否已无自动重试路径(已结算或处于终态)。
     *
     * @param target   结算目标
     * @param existing 既有台账行,可为空
     * @return 无需继续结算返回true
     */
    private boolean isFinished(SettlementTarget target, TornStockPortfolioAnnualSettlementDO existing) {
        if (existing == null) {
            return false;
        }
        if (StockAnnualSettlementStatusEnum.SETTLED.getCode().equals(existing.getSettlementStatus())) {
            log.info("VIP股票年度结算-已结算,幂等短路: portfolioCode={}, settleYear={}",
                    target.portfolioCode(), target.settleYear());
            return true;
        }
        if (!StockAnnualSettlementStatusEnum.isRetryableStatus(existing.getSettlementStatus())) {
            log.warn("VIP股票年度结算-状态不可自动重试,跳过: portfolioCode={}, settleYear={}, settlementStatus={}",
                    target.portfolioCode(), target.settleYear(), existing.getSettlementStatus());
            return true;
        }
        return false;
    }

    /**
     * Gate A:边界桶轮次必须已COMPLETED,否则该年度最后一桶的策略事实可能尚未全部落库。
     * <p>
     * 通过时返回该边界轮次供Gate B复用其完成时刻,不重复查询;Gate B不能以{@code boundaryTime}为锚点:
     * 轮次事务在{@code completeRound}之前无条件回写槽位,槽位{@code update_time}必然不早于边界时点。
     *
     * @param target   结算目标
     * @param existing 既有台账行,可为空
     * @return 已完成的边界轮次;未通过时已落降级状态并返回null
     */
    private TornStockMarketRoundDO requireCompletedBoundaryRound(SettlementTarget target,
                                                                 TornStockPortfolioAnnualSettlementDO existing) {
        TornStockMarketRoundDO round = roundDAO.selectByRoundTime(target.boundaryBarStart());
        String roundStatus = round == null ? null : round.getRoundStatus();
        if (round != null && StockRoundStatusEnum.COMPLETED.getCode().equals(roundStatus)) {
            return round;
        }
        degrade(target, existing, StockAnnualSettlementStatusEnum.PENDING_BOUNDARY,
                "边界桶轮次未完成: boundaryBarStart=" + target.boundaryBarStart() + ", roundStatus=" + roundStatus);
        return null;
    }

    /**
     * Gate B:边界之后不得发生任何资金变动,否则读到的状态已不是边界状态。
     * <p>
     * B1 排除新年度第一桶的资金动作(边界后24小时内无新的α批次动作);
     * B2 排除"边界之前已确认、边界之后才结算"的资金动作:这类写入不产生新的entry_time或exit_time,
     * 只有槽位写入事实能证明边界状态未被破坏,因此锚点是边界轮次的完成时刻而不是边界时点。
     *
     * @param target        结算目标
     * @param existing      既有台账行,可为空
     * @param slots         正式仓槽位
     * @param boundaryRound 已完成的边界桶轮次
     * @return 通过返回true;未通过时已落降级状态并返回false
     */
    private boolean passNoPostBoundaryChangeGate(SettlementTarget target,
                                                 TornStockPortfolioAnnualSettlementDO existing,
                                                 List<TornStockPortfolioSlotDO> slots,
                                                 TornStockMarketRoundDO boundaryRound) {
        List<TornStockVirtualBatchDO> actionBatches = batchDAO.selectAlphaActionBatches(target.portfolioCode(),
                target.boundaryTime(), target.boundaryTime().plusDays(1));
        if (!actionBatches.isEmpty()) {
            String batchNos = actionBatches.stream().map(TornStockVirtualBatchDO::getBatchNo)
                    .filter(Objects::nonNull).collect(Collectors.joining(","));
            degrade(target, existing, StockAnnualSettlementStatusEnum.DEGRADED_NOT_PROVABLE,
                    "边界后已发生资金变动: 批次=" + batchNos);
            return false;
        }
        LocalDateTime boundaryRoundCompletedAt = boundaryRound.getCompletedAt();
        if (boundaryRoundCompletedAt == null) {
            degrade(target, existing, StockAnnualSettlementStatusEnum.DEGRADED_NOT_PROVABLE,
                    "边界桶轮次缺少完成时间,边界状态不可证");
            return false;
        }
        TornStockPortfolioSlotDO rewritten = slots.stream()
                .filter(slot -> slot.getUpdateTime() != null
                        && slot.getUpdateTime().isAfter(boundaryRoundCompletedAt))
                .findFirst().orElse(null);
        if (rewritten != null) {
            degrade(target, existing, StockAnnualSettlementStatusEnum.DEGRADED_NOT_PROVABLE,
                    "边界轮次完成后槽位被再次写入: slotNo=" + rewritten.getSlotNo()
                            + ", updateTime=" + rewritten.getUpdateTime()
                            + ", boundaryRoundCompletedAt=" + boundaryRoundCompletedAt);
            return false;
        }
        return true;
    }

    /**
     * 解析首笔α批次入场日(缺失时回退槽位创建日)。
     * <p>
     * 只返回原始首笔入场日,不做年度钳制:按被结算年钳制只写在
     * {@link StockAnnualSettlementCalculator},年度连续性判定也继续使用该原始年度。
     *
     * @param portfolioCode 组合编码
     * @param slots         正式仓槽位
     * @return 首笔入场日;两者皆缺失时返回null
     */
    private LocalDate resolveFirstEntryDate(String portfolioCode, List<TornStockPortfolioSlotDO> slots) {
        LocalDateTime earliestEntryTime = batchDAO.selectEarliestEntryTime(portfolioCode);
        if (earliestEntryTime != null) {
            return earliestEntryTime.toLocalDate();
        }
        return slots.stream().map(TornStockPortfolioSlotDO::getCreateTime).filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate).findFirst().orElse(null);
    }

    /**
     * 解析本次结算前的累计已提取,并保证台账年度连续。
     *
     * @param target         结算目标
     * @param existing       既有台账行,可为空
     * @param firstEntryYear 首笔α批次入场日的自然年(不做年度钳制)
     * @return 累计已提取;上一年度未结算或年度断链时返回null(已落BLOCKED_PRIOR_YEAR)
     */
    private BigDecimal resolveCumulativeBefore(SettlementTarget target,
                                               TornStockPortfolioAnnualSettlementDO existing,
                                               int firstEntryYear) {
        TornStockPortfolioAnnualSettlementDO prior = settlementDAO.selectLatestSettledBefore(
                target.portfolioCode(), RULE_VERSION, target.settleYear());
        if (prior == null) {
            if (target.settleYear() > firstEntryYear) {
                degrade(target, existing, StockAnnualSettlementStatusEnum.BLOCKED_PRIOR_YEAR,
                        "上一年度未结算: settleYear=" + target.settleYear() + ", 首个应结算年度=" + firstEntryYear);
                return null;
            }
            return BigDecimal.ZERO;
        }
        if (prior.getSettleYear() == null || prior.getSettleYear() != target.settleYear() - 1) {
            degrade(target, existing, StockAnnualSettlementStatusEnum.BLOCKED_PRIOR_YEAR,
                    "上一年度未结算,禁止跳年结算: 最近已结算年度=" + prior.getSettleYear());
            return null;
        }
        return prior.getCumulativeExtractedAfter();
    }

    /**
     * 加载边界行情桶并只保留开放持仓股票中"可用且价格为正"的bar。
     * <p>
     * 必须按 {@code bar_start_time} 精确取桶:以bar_start_time为上界的最新可用bar查询在00:00时点
     * 会排除23:45桶,不得用于年度边界。
     *
     * @param boundaryBarStart 边界行情桶起点
     * @param openPositions    边界开放持仓批次
     * @return 股票ID到边界bar的索引;无开放持仓时返回空Map
     */
    private Map<Integer, TornStockMarketBar15mDO> loadBoundaryBars(LocalDateTime boundaryBarStart,
                                                                   List<TornStockVirtualBatchDO> openPositions) {
        Set<Integer> stocksIds = openPositions.stream().map(TornStockVirtualBatchDO::getStocksId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (stocksIds.isEmpty()) {
            return Map.of();
        }
        List<TornStockMarketBar15mDO> bars = barDAO.selectByBarStartTime(boundaryBarStart,
                Stock15mBarBuildService.BUILD_VERSION);
        Map<Integer, TornStockMarketBar15mDO> boundaryBars = new HashMap<>();
        for (TornStockMarketBar15mDO bar : bars) {
            if (!stocksIds.contains(bar.getStocksId()) || !Boolean.TRUE.equals(bar.getUsable())
                    || bar.getLastPrice() == null || bar.getLastPrice().signum() <= 0) {
                continue;
            }
            boundaryBars.put(bar.getStocksId(), bar);
        }
        return boundaryBars;
    }

    /**
     * 汇总正式仓可用现金。
     *
     * @param slots 正式仓槽位
     * @return 可用现金合计
     */
    private BigDecimal sumAvailableCash(List<TornStockPortfolioSlotDO> slots) {
        return slots.stream().map(TornStockPortfolioSlotDO::getAvailableCash).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 汇总正式仓预留资金。
     *
     * @param slots 正式仓槽位
     * @return 预留资金合计
     */
    private BigDecimal sumReservedCash(List<TornStockPortfolioSlotDO> slots) {
        return slots.stream().map(TornStockPortfolioSlotDO::getReservedCash).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 收集边界开放持仓的股票简称(保持批次顺序,去重)。
     *
     * @param openPositions 边界开放持仓批次
     * @return 股票简称列表
     */
    private List<String> collectOpenPositionStocks(List<TornStockVirtualBatchDO> openPositions) {
        return openPositions.stream().map(TornStockVirtualBatchDO::getStocksShortname).filter(Objects::nonNull)
                .distinct().toList();
    }

    /**
     * 构建待落库的已结算台账行。
     *
     * @param target            结算目标
     * @param result            结算计算结果
     * @param equityResult      权益计算结果
     * @param openPositionCount 边界开放持仓批次数
     * @param boundaryBarStart  边界行情桶起点
     * @param boundaryBars      边界行情索引
     * @param cumulativeBefore  本次结算前累计已提取
     * @return 待落库的台账行
     */
    private TornStockPortfolioAnnualSettlementDO buildSettledRow(SettlementTarget target, SettlementResult result,
                                                                 PortfolioEquityCalculator.EquityResult equityResult,
                                                                 int openPositionCount,
                                                                 LocalDateTime boundaryBarStart,
                                                                 Map<Integer, TornStockMarketBar15mDO> boundaryBars,
                                                                 BigDecimal cumulativeBefore) {
        TornStockPortfolioAnnualSettlementDO row = new TornStockPortfolioAnnualSettlementDO();
        row.setPortfolioCode(target.portfolioCode());
        row.setSettleYear(target.settleYear());
        row.setRuleVersion(RULE_VERSION);
        row.setBoundaryTime(target.boundaryTime());
        row.setBoundaryBarStartTime(boundaryBarStart);
        row.setBoundaryBarDigest(buildBoundaryBarDigest(boundaryBars));
        row.setInitialCash(StockPortfolioService.VIP_ALPHA_INITIAL_CASH);
        row.setOpeningEquity(result.openingEquity());
        row.setClosingMarketValue(result.closingMarketValue());
        row.setClosingEquity(equityResult.equity());
        row.setCumulativeExtractedBefore(cumulativeBefore);
        row.setExtractedAmount(result.extractedAmount());
        row.setCumulativeExtractedAfter(result.cumulativeExtractedAfter());
        row.setYearReturn(result.yearReturn());
        row.setCoverageDays(result.coverageDays());
        row.setPartialYear(result.partialYear());
        row.setAnnualizedReturn(result.annualizedReturn());
        row.setOpenPositionCount(openPositionCount);
        row.setSettlementStatus(StockAnnualSettlementStatusEnum.SETTLED.getCode());
        row.setLastAttemptAt(target.businessNow());
        return row;
    }

    /**
     * 计算边界行情证明摘要。
     * <p>
     * 按 {@code stocks_id} 升序将 {@code stocksId:barId:lastPrice} 以逗号拼接后取SHA-256,
     * 与通知载荷哈希共用同一摘要实现;无开放持仓时摘要为空串的SHA-256。
     *
     * @param boundaryBars 边界行情索引
     * @return 64位十六进制SHA-256摘要
     */
    private String buildBoundaryBarDigest(Map<Integer, TornStockMarketBar15mDO> boundaryBars) {
        String joined = boundaryBars.values().stream()
                .sorted(Comparator.comparing(TornStockMarketBar15mDO::getStocksId))
                .map(bar -> bar.getStocksId() + ":" + bar.getId() + ":" + bar.getLastPrice().toPlainString())
                .collect(Collectors.joining(","));
        return StockHashUtils.sha256(joined);
    }

    /**
     * 在单一事务内以单语句UPSERT落库已结算台账行并创建年度报告PENDING通知行。
     * <p>
     * 冲突时补齐既有降级行而不是放弃本次提取;落库后按业务唯一键回读,金额与派生量以数据库行为准,
     * 回读失败时不建通知,避免产出与台账不一致的年报。
     *
     * @param target             结算目标
     * @param computed           已结算台账行
     * @param openPositionStocks 边界开放持仓股票简称
     * @return 待投递的年度报告;金额写入后无法回读时为null
     */
    private AnnualSettlementOutcome persistSettled(SettlementTarget target,
                                                   TornStockPortfolioAnnualSettlementDO computed,
                                                   List<String> openPositionStocks) {
        settlementDAO.upsertSettled(computed, target.businessNow());
        TornStockPortfolioAnnualSettlementDO settled = settlementDAO.selectByBusinessKey(
                target.portfolioCode(), target.settleYear(), RULE_VERSION);
        if (settled == null || settled.getId() == null) {
            log.error("VIP股票年度结算-金额写入后无法回读,不建年报通知: portfolioCode={}, settleYear={}",
                    target.portfolioCode(), target.settleYear());
            return null;
        }
        LocalDate coverageStartDate = target.boundaryTime().toLocalDate().minusDays(settled.getCoverageDays());
        String reportText = renderer.render(new AnnualReportData(settled.getSettleYear(),
                Boolean.TRUE.equals(settled.getPartialYear()), coverageStartDate, settled.getCoverageDays(),
                settled.getYearReturn(), settled.getAnnualizedReturn(), settled.getExtractedAmount(),
                settled.getCumulativeExtractedAfter()));
        TornStockNoticeAuditDO notice = noticeService.saveOrReusePendingNotice(
                buildNoticePayload(settled, openPositionStocks), reportText, target.businessNow());
        settlementDAO.updateNoticeById(settled.getId(), notice.getId(), notice.getSendStatus(),
                target.businessNow());
        log.info("VIP股票年度结算-已结算: portfolioCode={}, settleYear={}, closingEquity={}, extractedAmount={},"
                        + " cumulativeExtractedAfter={}, noticeId={}",
                target.portfolioCode(), target.settleYear(), settled.getClosingEquity(),
                settled.getExtractedAmount(), settled.getCumulativeExtractedAfter(), notice.getId());
        return new AnnualSettlementOutcome(notice, reportText);
    }

    /**
     * 构建年度报告通知载荷。
     *
     * @param row                已结算台账行
     * @param openPositionStocks 边界开放持仓股票简称
     * @return 年度报告通知载荷
     */
    private AnnualSettlementNoticePayload buildNoticePayload(TornStockPortfolioAnnualSettlementDO row,
                                                             List<String> openPositionStocks) {
        return new AnnualSettlementNoticePayload(row.getSettleYear(), row.getBoundaryTime(),
                row.getBoundaryBarStartTime(), row.getBoundaryBarDigest(),
                row.getBoundaryTime().minusDays(1).toLocalDate(), row.getId(), row.getRuleVersion(),
                row.getInitialCash(), row.getOpeningEquity(), row.getClosingCash(), row.getClosingReserved(),
                row.getClosingMarketValue(), row.getClosingEquity(), row.getCumulativeExtractedBefore(),
                row.getExtractedAmount(), row.getCumulativeExtractedAfter(), row.getYearReturn(),
                row.getCoverageDays(), Boolean.TRUE.equals(row.getPartialYear()), row.getAnnualizedReturn(),
                openPositionStocks);
    }

    /**
     * 落降级或阻断状态:只写状态、原因与尝试时间,绝不写任何金额字段。
     * <p>
     * 回写带 {@code settlement_status <> SETTLED} 守卫:已结算行不得被降级覆盖,
     * 影响行数为0时只告警,不改变既有台账事实。
     *
     * @param target   结算目标
     * @param existing 既有台账行,可为空
     * @param status   目标状态(必须为非SETTLED状态)
     * @param reason   降级或阻断原因
     */
    private void degrade(SettlementTarget target, TornStockPortfolioAnnualSettlementDO existing,
                         StockAnnualSettlementStatusEnum status, String reason) {
        String degradeReason = truncate(reason);
        TornStockPortfolioAnnualSettlementDO row = existing;
        if (row == null) {
            row = new TornStockPortfolioAnnualSettlementDO();
            row.setPortfolioCode(target.portfolioCode());
            row.setSettleYear(target.settleYear());
            row.setBoundaryTime(target.boundaryTime());
            row.setRuleVersion(RULE_VERSION);
            row.setSettlementStatus(status.getCode());
            row.setDegradeReason(degradeReason);
            row.setLastAttemptAt(target.businessNow());
            if (settlementDAO.insertIgnoreConflict(row) > 0) {
                logDegrade(target, status, degradeReason);
                return;
            }
            row = settlementDAO.selectByBusinessKey(target.portfolioCode(), target.settleYear(), RULE_VERSION);
            if (row == null) {
                log.error("VIP股票年度结算-降级状态写入失败: portfolioCode={}, settleYear={}, settlementStatus={}",
                        target.portfolioCode(), target.settleYear(), status.getCode());
                return;
            }
        }
        row.setSettlementStatus(status.getCode());
        row.setDegradeReason(degradeReason);
        int updated = settlementDAO.updateDegradedById(row, target.businessNow());
        if (updated == 0) {
            log.warn("VIP股票年度结算-降级状态未写入,台账行已是已结算或被清除: settlementId={}, settlementStatus={}",
                    row.getId(), status.getCode());
            return;
        }
        logDegrade(target, status, degradeReason);
    }

    /**
     * 输出降级或阻断日志,状态语义决定日志级别。
     *
     * @param target        结算目标
     * @param status        降级状态
     * @param degradeReason 降级原因
     */
    private void logDegrade(SettlementTarget target, StockAnnualSettlementStatusEnum status, String degradeReason) {
        if (StockAnnualSettlementStatusEnum.PENDING_BOUNDARY.equals(status)) {
            log.warn("VIP股票年度结算-边界未就绪,等待窗口内重试: portfolioCode={}, settleYear={}, reason={}",
                    target.portfolioCode(), target.settleYear(), degradeReason);
            return;
        }
        log.error("VIP股票年度结算-{},转人工核验: portfolioCode={}, settleYear={}, reason={}",
                status.getChineseDisplay(), target.portfolioCode(), target.settleYear(), degradeReason);
    }

    /**
     * 截断降级原因,避免超出台账列宽。
     *
     * @param reason 原始原因
     * @return 截断后的原因
     */
    private String truncate(String reason) {
        if (reason == null || reason.length() <= MAX_DEGRADE_REASON_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_DEGRADE_REASON_LENGTH - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }

    /**
     * 年度结算目标(同一结算尝试内不变的边界与业务时间)。
     *
     * @param portfolioCode    组合编码
     * @param settleYear       被结算的自然年
     * @param boundaryTime     年度边界时点
     * @param boundaryBarStart 边界行情桶起点
     * @param businessNow      业务时间
     */
    private record SettlementTarget(
            String portfolioCode,
            int settleYear,
            LocalDateTime boundaryTime,
            LocalDateTime boundaryBarStart,
            LocalDateTime businessNow) {
    }

    /**
     * 年度结算产出:待投递的年度报告通知与正文。
     *
     * @param notice     已落库的年度报告通知(含主键)
     * @param reportText 年度报告正文
     */
    public record AnnualSettlementOutcome(
            TornStockNoticeAuditDO notice,
            String reportText) {
    }
}
