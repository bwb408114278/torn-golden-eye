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
 * 每月初(或阶段B冷启动)调用 {@link #initCurrentMonth()} 为全部股票构建当月
 * {@link TornStockMonthlyStateDO} 草稿,基于 {@link SysSettingManager#getStockPersonalities()}
 * 映射风格、按15分钟bar数量判定成熟度、风险等级默认NONE,完成人工或流程确认后由
 * {@link #confirmDraftStates(LocalDate)} 批量转CONFIRMED。
 *
 * <h3>核心规则</h3>
 * <ul>
 *   <li>风格来源: sys_setting.STOCK_PERSONALITY 配置, StockPersonalityEnum编码与
 *       {@link StockStrategyFitEnum} 一致,直接valueOf映射;配置缺失时stylePrior=null(fail-closed,禁止默认STEADY)</li>
 *   <li>成熟度: 按15分钟bar数量分级,>= {@value #MATURE_BAR_THRESHOLD} (30天)为M4_MATURE,
 *       >= {@value #SEASONED_BAR_THRESHOLD} (7天)为M3_SEASONED,
 *       >= {@value #PROVISIONAL_BAR_THRESHOLD} (1天)为M2_PROVISIONAL,
 *       >0为M1_EARLY,无bar为M0_UNMATURE</li>
 *   <li>风险等级: 初始化阶段统一 {@link StockRiskLevelEnum#NONE}。
 *       完整风险计算需要全窗口日级对数趋势、连续负月比例和最大回撤等多维度指标,
 *       当前阶段仅有15分钟bar数据,不足以支持完整风险分级。
 *       NONE表示"暂无明显风险",CONFIRMED前需人工复核风险等级</li>
 *   <li>幂等: 当月已存在CONFIRMED状态的股票跳过,不重复初始化</li>
 *   <li>开仓批次固化: 月度状态一经CONFIRMED即作为当月开仓批次的状态基准,次月变化不回写旧批次</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.12
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
     * 系统确认人标识
     */
    private static final String CONFIRMED_BY_SYSTEM = "SYSTEM";
    /**
     * 成熟度阈值: 30天bar数(30天 * 24小时 * 4桶/小时)
     */
    static final int MATURE_BAR_THRESHOLD = 2880;
    /**
     * 成熟度阈值: 7天bar数(7天 * 24小时 * 4桶/小时)
     */
    static final int SEASONED_BAR_THRESHOLD = 672;
    /**
     * 成熟度阈值: 1天bar数(24小时 * 4桶/小时)
     */
    static final int PROVISIONAL_BAR_THRESHOLD = 96;
    /**
     * 无bar计数
     */
    private static final int ZERO_BAR = 0;

    private final TornStocksDAO tornStocksDao;
    private final TornStockMonthlyStateDAO monthlyStateDao;
    private final TornStockMarketBar15mDAO bar15mDao;
    private final SysSettingManager sysSettingManager;

    // ==================== 对外方法 ====================

    /**
     * 为全部股票初始化当月风格/成熟度/风险快照
     * <p>
     * 以当月1日作为effectiveMonth,执行以下流程:
     * <ol>
     *   <li>获取全部股票列表</li>
     *   <li>查询当月已CONFIRMED状态的月度状态,这些股票将跳过初始化(幂等保护)</li>
     *   <li>从sys_setting读取STOCK_PERSONALITY配置,构建股票简称 -> 风格映射</li>
     *   <li>对每支未确认的股票构建DRAFT状态月度记录: 风格来自配置(fail-closed)、
     *       成熟度按bar数量分级、风险等级NONE、metricSnapshot存JSON快照</li>
     *   <li>批量保存草稿记录</li>
     * </ol>
     * 风格缺失时stylePrior=null,后续资格判断会拒绝该股票正式买入,禁止默认STEADY。
     *
     * @return 本次初始化新建的草稿记录数量;全部已确认时返回0
     */
    public int initCurrentMonth() {
        LocalDate effectiveMonth = LocalDate.now().withDayOfMonth(1);
        List<TornStocksDO> allStocks = tornStocksDao.list();
        if (CollectionUtils.isEmpty(allStocks)) {
            log.warn("月度状态初始化-股票列表为空,跳过, effectiveMonth={}", effectiveMonth);
            return 0;
        }

        Set<Integer> confirmedStockIds = loadConfirmedStockIds(effectiveMonth);
        if (confirmedStockIds.size() == allStocks.size()) {
            log.info("月度状态初始化-当月[{}]全部{}支股票已确认,无需初始化",
                    effectiveMonth, allStocks.size());
            return 0;
        }

        Map<String, StockStrategyFitEnum> styleMap = loadStyleMap();
        LocalDateTime now = LocalDateTime.now();
        List<TornStockMonthlyStateDO> draftStates = allStocks.stream()
                .filter(stock -> !confirmedStockIds.contains(stock.getId()))
                .map(stock -> buildDraftState(stock, effectiveMonth, styleMap, now))
                .toList();

        if (draftStates.isEmpty()) {
            log.info("月度状态初始化-当月[{}]无待初始化股票", effectiveMonth);
            return 0;
        }

        monthlyStateDao.saveBatch(draftStates);
        log.info("月度状态初始化-完成, effectiveMonth={}, 待初始化={}, 新建草稿={}",
                effectiveMonth, allStocks.size() - confirmedStockIds.size(), draftStates.size());
        return draftStates.size();
    }

    /**
     * 将指定月份的全部DRAFT状态月度记录批量转为CONFIRMED
     * <p>
     * 用于 {@link #initCurrentMonth()} 完成后由人工或流程触发确认。将stateStatus从DRAFT
     * 改为CONFIRMED,并填充confirmedAt为当前时间、confirmedBy为 {@value #CONFIRMED_BY_SYSTEM}。
     * 已CONFIRMED或RETIRED的记录不受影响。
     *
     * @param effectiveMonth 待确认的生效月份(当月1日)
     * @return 本次确认的记录数量;无DRAFT记录时返回0
     */
    public int confirmDraftStates(LocalDate effectiveMonth) {
        List<TornStockMonthlyStateDO> draftStates = monthlyStateDao.lambdaQuery()
                .eq(TornStockMonthlyStateDO::getEffectiveMonth, effectiveMonth)
                .eq(TornStockMonthlyStateDO::getStateStatus, StockMonthlyStateStatusEnum.DRAFT.getCode())
                .list();
        if (CollectionUtils.isEmpty(draftStates)) {
            log.info("月度状态确认-当月[{}]无DRAFT记录,跳过", effectiveMonth);
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        for (TornStockMonthlyStateDO state : draftStates) {
            state.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
            state.setConfirmedAt(now);
            state.setConfirmedBy(CONFIRMED_BY_SYSTEM);
        }
        monthlyStateDao.updateBatchById(draftStates);
        log.info("月度状态确认-完成, effectiveMonth={}, 确认DRAFT记录={}", effectiveMonth, draftStates.size());
        return draftStates.size();
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
     * 按股票的15分钟bar数量判定成熟度
     * <p>
     * 分级规则:
     * <ul>
     *   <li>>= {@value #MATURE_BAR_THRESHOLD} (30天): {@link StockMaturityEnum#M4_MATURE}</li>
     *   <li>>= {@value #SEASONED_BAR_THRESHOLD} (7天): {@link StockMaturityEnum#M3_SEASONED}</li>
     *   <li>>= {@value #PROVISIONAL_BAR_THRESHOLD} (1天): {@link StockMaturityEnum#M2_PROVISIONAL}</li>
     *   <li>>0: {@link StockMaturityEnum#M1_EARLY}</li>
     *   <li>无bar: {@link StockMaturityEnum#M0_UNMATURE}</li>
     * </ul>
     *
     * @param stocksId 股票ID
     * @return 成熟度枚举
     */
    private StockMaturityEnum determineMaturity(Integer stocksId) {
        long barCount = bar15mDao.lambdaQuery()
                .eq(TornStockMarketBar15mDO::getStocksId, stocksId)
                .count();
        if (barCount >= MATURE_BAR_THRESHOLD) {
            return StockMaturityEnum.M4_MATURE;
        }
        if (barCount >= SEASONED_BAR_THRESHOLD) {
            return StockMaturityEnum.M3_SEASONED;
        }
        if (barCount >= PROVISIONAL_BAR_THRESHOLD) {
            return StockMaturityEnum.M2_PROVISIONAL;
        }
        if (barCount > ZERO_BAR) {
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
     *   <li>maturity: 按15分钟bar数量分级</li>
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
                                                    LocalDateTime now) {
        Integer stocksId = stock.getId();
        String shortname = stock.getStocksShortname();
        String shortnameUpper = shortname == null ? "" : shortname.toUpperCase();
        StockStrategyFitEnum style = styleMap.get(shortnameUpper);
        StockMaturityEnum maturity = determineMaturity(stocksId);

        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(stocksId);
        state.setStocksShortname(shortname);
        state.setEffectiveMonth(effectiveMonth);
        state.setStrategyFitPrior(style == null ? null : style.getCode());
        state.setMaturity(maturity.getCode());
        state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
        state.setSuggestedPersonality(null);
        state.setPreviousPersonality(null);
        state.setManualOverride(false);
        state.setOverrideReason(null);
        state.setMetricSnapshot(buildMetricSnapshot(stock, style, maturity, now));
        state.setPersonalityRuleVersion(PERSONALITY_RULE_VERSION);
        state.setRiskRuleVersion(RISK_RULE_VERSION);
        state.setEvidenceStartTime(null);
        state.setEvidenceEndTime(now);
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

    // ==================== 私有方法: 已确认股票加载 ====================

    /**
     * 加载当月已CONFIRMED状态的股票ID集合
     * <p>
     * 用于幂等保护: 已确认的股票不再重复初始化。
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
}
