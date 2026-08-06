package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockPersonalityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMonthlyStateStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.utils.JsonUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 股票月度风格状态初始化服务 - 为全部股票按当月指标快照生成风格/成熟度/风险等级草稿记录
 * <p>
 * 每月初(或阶段B冷启动)调用 {@link #initCurrentMonth()} 为缺少当月有效状态的股票构建
 * {@link TornStockMonthlyStateDO} 草稿,基于 {@link SysSettingManager#getStockPersonalities()}
 * 映射风格、按证据自然日跨度判定成熟度、风险等级默认NONE,完成人工或流程确认后由
 * {@link #confirmDraftStates(LocalDate, String)} 批量转CONFIRMED。
 *
 * <h3>核心规则</h3>
 * <ul>
 *   <li>风格来源: sys_setting.STOCK_PERSONALITY 配置, StockPersonalityEnum编码与
 *       {@link StockStrategyFitEnum} 一致,直接valueOf映射;配置缺失时stylePrior=null(fail-closed,禁止默认STEADY)</li>
 *   <li>成熟度: 按证据首尾时间的自然日跨度分级,不使用bar数量换算</li>
 *   <li>风险等级: 初始化阶段统一 {@link StockRiskLevelEnum#NONE}。
 *       完整风险计算需要全窗口日级对数趋势、连续负月比例和最大回撤等多维度指标,
 *       当前阶段仅有15分钟bar数据,不足以支持完整风险分级。
 *       NONE表示"暂无明显风险",CONFIRMED前需人工复核风险等级</li>
 *   <li>幂等: 当月已存在任意有效状态(DRAFT/CONFIRMED/RETIRED)的股票跳过初始化,
 *       插入使用PostgreSQL {@code ON CONFLICT DO NOTHING},与数据库部分唯一索引
 *       {@code uk_stock_monthly_state_stock_month} 保持一致,重复启动/并发不抛异常、不重复、不覆盖</li>
 *   <li>开仓批次固化: 月度状态一经CONFIRMED即作为当月开仓批次的状态基准,次月变化不回写旧批次</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockMonthlyStateInitService {
    /**
     * 人格分类规则版本
     */
    private static final String PERSONALITY_RULE_VERSION = "1.0.0";
    /**
     * 风险分级规则版本
     */
    private static final String RISK_RULE_VERSION = "1.0.0";

    /**
     * 成熟度最高等级的自然日边界
     */
    static final int MATURE_DAYS = 365;
    /**
     * 成熟度较成熟等级的自然日边界
     */
    static final int SEASONED_DAYS = 240;
    /**
     * 成熟度暂定等级的自然日边界
     */
    static final int PROVISIONAL_DAYS = 120;
    /**
     * 成熟度早期等级的自然日边界
     */
    static final int EARLY_DAYS = 60;

    private final TornStocksDAO tornStocksDao;
    private final TornStockMonthlyStateDAO monthlyStateDao;
    private final TornStockMarketBar15mDAO bar15mDao;
    private final SysSettingManager sysSettingManager;
    private final StockMarketClock marketClock;

    // ==================== 对外方法 ====================

    /**
     * 为全部股票初始化当月风格/成熟度/风险快照
     * <p>
     * 以当月1日作为effectiveMonth,执行以下流程:
     * <ol>
     *   <li>获取全部股票列表</li>
     *   <li>一次查询当月任意有效状态(DRAFT/CONFIRMED/RETIRED)的股票ID集合,
     *       这些股票全部跳过初始化,避免与数据库部分唯一索引
     *       {@code uk_stock_monthly_state_stock_month} 冲突(幂等保护)</li>
     *   <li>查询当月已CONFIRMED股票ID集合,仅用于可观测日志</li>
     *   <li>从sys_setting读取STOCK_PERSONALITY配置,构建股票简称 -> 风格映射</li>
     *   <li>仅对既无任何有效状态的股票构建DRAFT状态月度记录</li>
     *   <li>使用PostgreSQL冲突安全批量插入(ON CONFLICT DO NOTHING),返回实际插入数,
     *       不覆盖任何已存在状态,重复键冲突被数据库吸收不抛异常</li>
     * </ol>
     * 风格缺失时stylePrior=null,后续资格判断会拒绝该股票正式买入,禁止默认STEADY。
     *
     * @return 本次初始化实际新建的草稿记录数量;全部股票已有有效状态时返回0
     */
    public int initCurrentMonth() {
        LocalDate effectiveMonth = marketClock.today().withDayOfMonth(1);
        List<TornStocksDO> allStocks = tornStocksDao.list();
        if (CollectionUtils.isEmpty(allStocks)) {
            log.warn("月度状态初始化-股票列表为空,跳过, effectiveMonth={}", effectiveMonth);
            return 0;
        }

        Set<Integer> existingStockIds = loadExistingStockIds(effectiveMonth);
        Set<Integer> confirmedStockIds = loadConfirmedStockIds(effectiveMonth);
        List<TornStocksDO> missingStocks = allStocks.stream()
                .filter(stock -> !existingStockIds.contains(stock.getId()))
                .toList();
        if (missingStocks.isEmpty()) {
            log.info("月度状态初始化-当月[{}]全部{}支股票已有任意有效状态,无需初始化, existingCount={}, confirmedCount={}",
                    effectiveMonth, allStocks.size(), existingStockIds.size(), confirmedStockIds.size());
            return 0;
        }

        Map<String, StockStrategyFitEnum> styleMap = loadStyleMap();
        LocalDateTime now = marketClock.now();
        LocalDateTime evidenceEnd = effectiveMonth.atStartOfDay();
        Map<Integer, TornStockMarketBar15mDO> evidenceRanges = loadEvidenceRanges(evidenceEnd);
        List<TornStockMonthlyStateDO> draftStates = missingStocks.stream()
                .map(stock -> buildDraftState(stock, effectiveMonth, styleMap,
                        evidenceRanges.get(stock.getId()), now, evidenceEnd))
                .toList();

        if (draftStates.isEmpty()) {
            log.info("月度状态初始化-当月[{}]无待初始化股票", effectiveMonth);
            return 0;
        }

        int insertedCount = monthlyStateDao.insertDraftStatesIgnoreConflict(draftStates);
        int conflictIgnoredCount = draftStates.size() - insertedCount;
        log.info("月度状态初始化-完成, effectiveMonth={}, existingCount={}, confirmedCount={}, "
                        + "candidateCount={}, insertedCount={}, conflictIgnoredCount={}",
                effectiveMonth, existingStockIds.size(), confirmedStockIds.size(),
                draftStates.size(), insertedCount, conflictIgnoredCount);
        return insertedCount;
    }


    /**
     * 人工确认指定月份的草稿状态。
     *
     * @param effectiveMonth 生效月份
     * @param confirmedBy    实际确认人
     * @return 本次确认的记录数量
     *
     */
    public int confirmDraftStates(LocalDate effectiveMonth, String confirmedBy) {
        if (confirmedBy == null || confirmedBy.isBlank()) {
            throw new IllegalArgumentException("确认人不能为空");
        }
        return confirmDraftStatesInternal(effectiveMonth, confirmedBy);
    }


    private int confirmDraftStatesInternal(LocalDate effectiveMonth, String confirmedBy) {
        List<TornStockMonthlyStateDO> draftStates = monthlyStateDao.lambdaQuery()
                .eq(TornStockMonthlyStateDO::getEffectiveMonth, effectiveMonth)
                .eq(TornStockMonthlyStateDO::getStateStatus, StockMonthlyStateStatusEnum.DRAFT.getCode())
                .list();
        if (CollectionUtils.isEmpty(draftStates)) {
            log.info("月度状态确认-当月[{}]无DRAFT记录,跳过", effectiveMonth);
            return 0;
        }

        LocalDateTime now = marketClock.now();
        for (TornStockMonthlyStateDO state : draftStates) {
            if (!isConfirmable(state)) {
                log.warn("月度状态确认-记录不完整,保留DRAFT: stocksId={}, effectiveMonth={}",
                        state.getStocksId(), effectiveMonth);
                continue;
            }
            state.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
            state.setConfirmedAt(now);
            state.setConfirmedBy(confirmedBy);
        }
        List<TornStockMonthlyStateDO> confirmableStates = draftStates.stream()
                .filter(state -> StockMonthlyStateStatusEnum.CONFIRMED.getCode().equals(state.getStateStatus()))
                .toList();
        if (confirmableStates.isEmpty()) {
            log.warn("月度状态确认-没有满足完整性要求的DRAFT, effectiveMonth={}", effectiveMonth);
            return 0;
        }
        monthlyStateDao.updateBatchById(confirmableStates);
        log.info("月度状态确认-完成, effectiveMonth={}, 确认DRAFT记录={}", effectiveMonth, confirmableStates.size());
        return confirmableStates.size();
    }

    /**
     * 校验月度状态是否满足CONFIRMED落库完整性要求。
     *
     * @param state 待确认状态
     * @return 满足完整性要求返回true
     */
    private boolean isConfirmable(TornStockMonthlyStateDO state) {
        return state.getStrategyFitPrior() != null && !state.getStrategyFitPrior().isBlank()
                && state.getMaturity() != null && !state.getMaturity().isBlank()
                && state.getRiskLevel() != null && !state.getRiskLevel().isBlank()
                && state.getSuggestedPersonality() != null && !state.getSuggestedPersonality().isBlank()
                && state.getEvidenceStartTime() != null
                && state.getEvidenceEndTime() != null
                && !state.getEvidenceStartTime().isAfter(state.getEvidenceEndTime());
    }

    // ==================== 私有方法: 风格映射 ====================

    /**
     * 从sys_setting.STOCK_PERSONALITY加载股票简称 -> 策略契合度风格映射
     * <p>
     * SysSettingManager.getStockPersonalities()返回的Map key已大写,值为
     * {@link StockPersonalityEnum};因 StockPersonalityEnum 与 {@link StockStrategyFitEnum}
     * 编码一致,直接valueOf映射。
     *
     * @return 股票简称(大写) -> 策略契合度风格映射;配置不存在时返回空Map
     */
    private Map<String, StockStrategyFitEnum> loadStyleMap() {
        Map<String, StockPersonalityEnum> personalityMap = sysSettingManager.getStockPersonalities();
        if (personalityMap == null || personalityMap.isEmpty()) {
            log.warn("月度状态初始化-STOCK_PERSONALITY配置为空,全部股票stylePrior将为null(fail-closed)");
            return Map.of();
        }
        Map<String, StockStrategyFitEnum> styleMap = new LinkedHashMap<>();
        for (Map.Entry<String, StockPersonalityEnum> entry : personalityMap.entrySet()) {
            try {
                styleMap.put(entry.getKey(), StockStrategyFitEnum.valueOf(entry.getValue().name()));
            } catch (IllegalArgumentException ignored) {
                log.warn("月度状态初始化-风格映射失败, 简称={}, personality={}, 跳过",
                        entry.getKey(), entry.getValue());
            }
        }
        return styleMap;
    }

    // ==================== 私有方法: 成熟度判定 ====================

    /**
     * 按证据首尾时间的自然日跨度判定成熟度
     * <p>
     * 分级规则:
     * <ul>
     *   <li>>= {@value #MATURE_DAYS}: {@link StockMaturityEnum#M4_MATURE}</li>
     *   <li>>= {@value #SEASONED_DAYS}: {@link StockMaturityEnum#M3_SEASONED}</li>
     *   <li>>= {@value #PROVISIONAL_DAYS}: {@link StockMaturityEnum#M2_PROVISIONAL}</li>
     *   <li>>= {@value #EARLY_DAYS}: {@link StockMaturityEnum#M1_EARLY}</li>
     *   <li>不足{@value #EARLY_DAYS}天或无bar: {@link StockMaturityEnum#M0_UNMATURE}</li>
     * </ul>
     *
     * @param evidenceRange 股票证据首尾bar时间
     * @return 成熟度枚举
     */
    private StockMaturityEnum determineMaturity(TornStockMarketBar15mDO evidenceRange) {
        if (evidenceRange == null || evidenceRange.getFirstSampleTime() == null
                || evidenceRange.getLastSampleTime() == null) {
            return StockMaturityEnum.M0_UNMATURE;
        }
        long evidenceDays = java.time.Duration.between(
                evidenceRange.getFirstSampleTime(), evidenceRange.getLastSampleTime()).toDays();
        if (evidenceDays >= MATURE_DAYS) {
            return StockMaturityEnum.M4_MATURE;
        }
        if (evidenceDays >= SEASONED_DAYS) {
            return StockMaturityEnum.M3_SEASONED;
        }
        if (evidenceDays >= PROVISIONAL_DAYS) {
            return StockMaturityEnum.M2_PROVISIONAL;
        }
        if (evidenceDays >= EARLY_DAYS) {
            return StockMaturityEnum.M1_EARLY;
        }
        return StockMaturityEnum.M0_UNMATURE;
    }

    // ==================== 私有方法: 草稿构建 ====================

    /**
     * 为单支股票构建DRAFT状态的月度状态记录
     * <p>
     * 字段填充规则:
     * <ul>
     *   <li>stylePrior: 从styleMap按股票简称大写查找,缺失时null(fail-closed)</li>
     *   <li>maturity: 按证据首尾时间的自然日跨度分级</li>
     *   <li>riskLevel: {@link StockRiskLevelEnum#NONE}</li>
     *   <li>metricSnapshot: JSON文本,记录分类时的stocksId/简称/风格来源/barCount/成熟度/初始化时间</li>
     *   <li>personalityRuleVersion/riskRuleVersion: 固定版本号</li>
     *   <li>stateStatus: {@link StockMonthlyStateStatusEnum#DRAFT}</li>
     *   <li>calculatedAt: 当前时间</li>
     * </ul>
     *
     * @param stock          股票DO
     * @param effectiveMonth 生效月份
     * @param styleMap       风格映射(简称大写 -> 风格)
     * @param now            计算时间
     * @return 初始化完成的草稿DO(尚未持久化)
     */
    private TornStockMonthlyStateDO buildDraftState(TornStocksDO stock,
                                                    LocalDate effectiveMonth,
                                                    Map<String, StockStrategyFitEnum> styleMap,
                                                    TornStockMarketBar15mDO evidenceRange,
                                                    LocalDateTime now,
                                                    LocalDateTime evidenceEnd) {
        Integer stocksId = stock.getId();
        String shortname = stock.getStocksShortname();
        String shortnameUpper = shortname == null ? "" : shortname.toUpperCase();
        StockStrategyFitEnum style = styleMap.get(shortnameUpper);
        StockMaturityEnum maturity = determineMaturity(evidenceRange);

        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(stocksId);
        state.setStocksShortname(shortname);
        state.setEffectiveMonth(effectiveMonth);
        state.setStrategyFitPrior(style == null ? null : style.getCode());
        state.setMaturity(maturity.getCode());
        state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
        state.setSuggestedPersonality(style == null ? null : style.getCode());
        state.setPreviousPersonality(null);
        state.setManualOverride(false);
        state.setOverrideReason(null);
        state.setMetricSnapshot(buildMetricSnapshot(stock, style, maturity, now));
        state.setPersonalityRuleVersion(PERSONALITY_RULE_VERSION);
        state.setRiskRuleVersion(RISK_RULE_VERSION);
        state.setEvidenceStartTime(evidenceRange == null ? null : evidenceRange.getFirstSampleTime());
        state.setEvidenceEndTime(evidenceRange == null ? null : min(evidenceRange.getLastSampleTime(), evidenceEnd));
        state.setStateStatus(StockMonthlyStateStatusEnum.DRAFT.getCode());
        state.setCalculatedAt(now);
        state.setConfirmedAt(null);
        state.setConfirmedBy(null);
        return state;
    }

    /**
     * 构建分类时指标快照JSON文本
     * <p>
     * 记录分类时刻的全部输入特征,便于后续审计与规则版本回溯。
     *
     * @param stock    股票DO
     * @param style    风格(可为null)
     * @param maturity 成熟度
     * @param now      快照时间
     * @return JSON文本
     */
    private String buildMetricSnapshot(TornStocksDO stock,
                                       StockStrategyFitEnum style,
                                       StockMaturityEnum maturity,
                                       LocalDateTime now) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("stocksId", stock.getId());
        snapshot.put("stocksShortname", stock.getStocksShortname());
        snapshot.put("currentPrice", stock.getCurrentPrice());
        snapshot.put("strategyFit", style == null ? null : style.getCode());
        snapshot.put("maturity", maturity.getCode());
        snapshot.put("riskLevel", StockRiskLevelEnum.NONE.getCode());
        snapshot.put("styleSource", style == null ? "MISSING_FAIL_CLOSED" : "STOCK_PERSONALITY_CONFIG");
        snapshot.put("snapshotTime", now.toString());
        return JsonUtils.objToJson(snapshot);
    }

    /**
     * 批量加载每支股票的证据首尾bar时间。
     *
     * @param evidenceEnd 证据截止时间
     * @return 股票ID到证据首尾bar的映射
     */
    private Map<Integer, TornStockMarketBar15mDO> loadEvidenceRanges(LocalDateTime evidenceEnd) {
        List<TornStockMarketBar15mDO> ranges = bar15mDao.selectEvidenceRanges(
                evidenceEnd, Stock15mBarBuildService.BUILD_VERSION);
        if (CollectionUtils.isEmpty(ranges)) {
            return Map.of();
        }
        return ranges.stream()
                .filter(range -> range.getStocksId() != null)
                .collect(Collectors.toMap(TornStockMarketBar15mDO::getStocksId,
                        value -> value, (left, right) -> left, LinkedHashMap::new));
    }

    /**
     * 返回两个时间中的较早值。
     *
     * @param left  左侧时间
     * @param right 右侧时间
     * @return 较早时间
     */
    private LocalDateTime min(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    // ==================== 私有方法: 已确认股票加载 ====================

    /**
     * 加载当月已CONFIRMED状态的股票ID集合
     * <p>
     * 仅用于可观测日志,不作为初始化INSERT过滤条件;初始化过滤使用
     * {@link #loadExistingStockIds(LocalDate)}。
     *
     * @param effectiveMonth 生效月份
     * @return 已确认股票ID集合;无记录时返回空Set
     */
    private Set<Integer> loadConfirmedStockIds(LocalDate effectiveMonth) {
        List<TornStockMonthlyStateDO> confirmed = monthlyStateDao.selectConfirmedByMonth(effectiveMonth);
        if (CollectionUtils.isEmpty(confirmed)) {
            return Set.of();
        }
        return confirmed.stream()
                .map(TornStockMonthlyStateDO::getStocksId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 加载当月存在任意有效状态(DRAFT/CONFIRMED/RETIRED)的股票ID集合
     * <p>
     * 用于初始化幂等过滤:同月每股票至多一行有效状态,只要已存在任意状态,
     * 都不得再INSERT该股票,避免触发数据库部分唯一索引冲突。
     *
     * @param effectiveMonth 生效月份
     * @return 当月已有任意有效状态的股票ID集合;无记录时返回空Set
     */
    private Set<Integer> loadExistingStockIds(LocalDate effectiveMonth) {
        List<Integer> existingIds = monthlyStateDao.selectExistingStockIdsByMonth(effectiveMonth);
        if (CollectionUtils.isEmpty(existingIds)) {
            return Set.of();
        }
        return new HashSet<>(existingIds);
    }
}
