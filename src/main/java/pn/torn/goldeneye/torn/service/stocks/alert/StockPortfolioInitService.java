package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 股票组合初始化服务 - 验证并补救正式与候选影子两个VIP组合的5个20亿槽位
 * <p>
 * 正式组合({@link StockPortfolioService#PORTFOLIO_CODE})与候选影子组合
 * ({@link StockPortfolioService#SHADOW_CANDIDATE_PORTFOLIO_CODE})各自由
 * {@value pn.torn.goldeneye.torn.service.stocks.alert.StockPortfolioService#SLOT_COUNT} 个
 * 独立槽位组成,每槽初始资金 {@value pn.torn.goldeneye.torn.service.stocks.alert.StockPortfolioService#INITIAL_CASH_PLAIN} 。
 * 本服务在应用启动或运维校验场景下,检查两个组合槽位的完整性与金额正确性:
 * 槽位缺失时按标准参数补建,初始资金或现金口径异常时记录警告但不擅自修改业务数据。
 *
 * <h3>核心规则</h3>
 * <ul>
 *   <li>槽位数量不足 {@value pn.torn.goldeneye.torn.service.stocks.alert.StockPortfolioService#SLOT_COUNT} 时补建缺失槽位</li>
 *   <li>槽位全部缺失时一次性创建5个标准初始槽位</li>
 *   <li>已存在且状态正确的槽位不做任何修改</li>
 *   <li>initialCash 不等于20亿时仅记录警告,不自动修正(避免覆盖人工调拨)</li>
 *   <li>availableCash + reservedCash 不等于 initialCash 时记录警告(资金口径异常)</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockPortfolioInitService {
    /**
     * 初始预留资金(新建槽位时reservedCash取此值)
     */
    private static final BigDecimal ZERO_RESERVED = BigDecimal.ZERO;
    /**
     * 资金差额容差(用于BigDecimal比较,避免scale差异导致误报)
     */
    private static final BigDecimal CASH_TOLERANCE = new BigDecimal("0.01");
    /**
     * 乐观锁初始版本号
     */
    private static final long INITIAL_LOCK_VERSION = 0L;
    /**
     * 需要验证与初始化的组合编码(正式 + 候选影子)
     */
    private static final List<String> PORTFOLIO_CODES = List.of(
            StockPortfolioService.PORTFOLIO_CODE,
            StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE);

    private final TornStockPortfolioSlotDAO portfolioSlotDAO;

    // ==================== 槽位验证与初始化 ====================

    /**
     * 验证并初始化正式与候选影子两个组合的5个20亿槽位
     * <p>
     * 对每个组合编码查询全部槽位,按以下顺序处理:
     * <ol>
     *   <li>槽位全部缺失:一次性创建5个标准初始槽位</li>
     *   <li>槽位数量不足:补建缺失序号的槽位</li>
     *   <li>逐个验证已存在槽位的initialCash与资金口径合理性,异常时记录警告</li>
     * </ol>
     * 已存在且状态正确的槽位不会被修改。返回true表示全部组合槽位完整且金额校验全部通过
     * (含本次补建后通过),返回false表示存在修复或校验告警,需运维关注。
     *
     * @return true表示槽位完整且金额校验全部通过;false表示存在修复或告警
     */
    public boolean verifyAndInitSlots() {
        boolean allValid = true;
        for (String portfolioCode : PORTFOLIO_CODES) {
            allValid = verifyAndInitPortfolio(portfolioCode) && allValid;
        }
        return allValid;
    }

    /**
     * 验证并初始化单个组合编码的5个20亿槽位。
     *
     * @param portfolioCode 组合编码
     * @return true表示该组合槽位完整且金额校验全部通过
     */
    private boolean verifyAndInitPortfolio(String portfolioCode) {
        List<TornStockPortfolioSlotDO> slots = portfolioSlotDAO.selectAllByPortfolioCode(portfolioCode);

        boolean repaired = false;
        if (slots == null || slots.isEmpty()) {
            log.warn("组合[{}]槽位全部缺失,创建{}个标准初始槽位",
                    portfolioCode, StockPortfolioService.SLOT_COUNT);
            createSlots(portfolioCode, 1, StockPortfolioService.SLOT_COUNT);
            return false;
        }

        Set<Integer> existingSlotNos = new HashSet<>();
        for (TornStockPortfolioSlotDO slot : slots) {
            if (slot.getSlotNo() != null) {
                existingSlotNos.add(slot.getSlotNo());
            }
        }

        List<Integer> missingSlotNos = IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                .filter(no -> !existingSlotNos.contains(no))
                .boxed()
                .toList();

        if (!missingSlotNos.isEmpty()) {
            log.warn("组合[{}]缺失槽位序号{},开始补建",
                    portfolioCode, missingSlotNos);
            for (Integer slotNo : missingSlotNos) {
                createSlots(portfolioCode, slotNo, slotNo);
            }
            repaired = true;
        }

        boolean allValid = validateExistingSlots(slots);
        if (repaired) {
            log.info("组合[{}]槽位补建完成,本次共补建{}个槽位",
                    portfolioCode, missingSlotNos.size());
            return false;
        }
        return allValid;
    }

    /**
     * 获取指定组合的槽位数量。
     *
     * @param portfolioCode 组合编码
     * @return 当前组合的槽位数量;组合不存在槽位时返回0
     */
    public int getSlotCount(String portfolioCode) {
        List<TornStockPortfolioSlotDO> slots = portfolioSlotDAO.selectAllByPortfolioCode(portfolioCode);
        if (slots == null || slots.isEmpty()) {
            return 0;
        }
        return slots.size();
    }

    /**
     * 获取正式VIP组合的槽位数量(兼容入口)。
     *
     * @return 正式组合槽位数量;组合不存在槽位时返回0
     */
    public int getSlotCount() {
        return getSlotCount(StockPortfolioService.PORTFOLIO_CODE);
    }

    // ==================== 私有方法 ====================

    /**
     * 批量创建指定组合的指定序号区间标准初始槽位(闭区间)
     * <p>
     * 使用 {@link StockPortfolioService#INITIAL_CASH} 作为initialCash与availableCash,
     * reservedCash置零,slotStatus置为 {@link StockSlotStatusEnum#AVAILABLE} ,
     * lockVersion初始化为 {@value #INITIAL_LOCK_VERSION} 。批量保存到数据库。
     *
     * @param portfolioCode 组合编码
     * @param fromNo        起始槽位序号(含)
     * @param toNo          结束槽位序号(含)
     */
    private void createSlots(String portfolioCode, int fromNo, int toNo) {
        List<TornStockPortfolioSlotDO> newSlots = IntStream.rangeClosed(fromNo, toNo)
                .mapToObj(slotNo -> buildStandardSlot(portfolioCode, slotNo))
                .toList();
        portfolioSlotDAO.saveBatch(newSlots);
        log.info("组合[{}]创建槽位序号{}~{}共{}个,每个初始资金{}",
                portfolioCode, fromNo, toNo, newSlots.size(),
                StockPortfolioService.INITIAL_CASH);
    }

    /**
     * 构建指定组合的一个标准初始槽位DO
     *
     * @param portfolioCode 组合编码
     * @param slotNo        槽位序号
     * @return 初始化完成的槽位DO(尚未持久化)
     */
    private TornStockPortfolioSlotDO buildStandardSlot(String portfolioCode, int slotNo) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setPortfolioCode(portfolioCode);
        slot.setSlotNo(slotNo);
        slot.setInitialCash(StockPortfolioService.INITIAL_CASH);
        slot.setAvailableCash(StockPortfolioService.INITIAL_CASH);
        slot.setReservedCash(ZERO_RESERVED);
        slot.setCurrentBatchId(null);
        slot.setSlotStatus(StockSlotStatusEnum.AVAILABLE.getCode());
        slot.setLockVersion(INITIAL_LOCK_VERSION);
        return slot;
    }

    /**
     * 验证已存在槽位的金额正确性
     * <p>
     * 逐个槽位检查:
     * <ul>
     *   <li>initialCash 是否等于 {@link StockPortfolioService#INITIAL_CASH} (容差 {@value #CASH_TOLERANCE} )</li>
     *   <li>availableCash + reservedCash 是否等于 initialCash (容差 {@value #CASH_TOLERANCE} )</li>
     * </ul>
     * 任意项不匹配时记录警告但不修改数据。所有槽位均通过时返回true。
     *
     * @param slots 已存在的槽位列表
     * @return true表示全部槽位金额校验通过;false表示存在告警
     */
    private boolean validateExistingSlots(List<TornStockPortfolioSlotDO> slots) {
        boolean allValid = true;
        for (TornStockPortfolioSlotDO slot : slots) {
            Integer slotNo = slot.getSlotNo();
            BigDecimal initialCash = slot.getInitialCash();
            BigDecimal availableCash = slot.getAvailableCash() == null
                    ? BigDecimal.ZERO : slot.getAvailableCash();
            BigDecimal reservedCash = slot.getReservedCash() == null
                    ? BigDecimal.ZERO : slot.getReservedCash();

            if (initialCash == null
                    || initialCash.subtract(StockPortfolioService.INITIAL_CASH).abs()
                            .compareTo(CASH_TOLERANCE) > 0) {
                log.warn("槽位[{}]initialCash={}与标准值{}不匹配,请人工核查",
                        slotNo, initialCash, StockPortfolioService.INITIAL_CASH);
                allValid = false;
            }

            BigDecimal cashSum = availableCash.add(reservedCash);
            if (initialCash != null
                    && cashSum.subtract(initialCash).abs().compareTo(CASH_TOLERANCE) > 0) {
                log.warn("槽位[{}]availableCash+reservedCash={}与initialCash={}不一致,请人工核查",
                        slotNo, cashSum, initialCash);
                allValid = false;
            }
        }
        return allValid;
    }
}
