package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 股票组合管理服务 - 维护5槽正式组合的整数股数、余款现金与槽内复利
 * <p>
 * 正式组合由 {@value #SLOT_COUNT} 个独立槽位组成,每槽初始资金 {@value #INITIAL_CASH_PLAIN} ,
 * 槽位之间资金不自动调拨。本服务封装槽位分配、预留、建仓占用、取消释放、卖出结算
 * 与组合权益计算等纯领域能力,所有金额运算使用 {@link BigDecimal}(精度18位,HALF_UP),
 * 股数一律取整数({@link Long})。卖出统一扣除 {@value #SELL_FEE_RATE_TEXT} 手续费。
 *
 * <h3>核心规则</h3>
 * <ul>
 *   <li>股数 = floor(可用资金 / 入场参考价)</li>
 *   <li>实际成本 = 股数 × 入场参考价,余款保留在原槽</li>
 *   <li>卖出所得 = 股数 × 卖出参考价 × 0.999,回笼到原槽实现槽内复利</li>
 *   <li>ENTRY_PENDING 预留槽位与预算;取消时释放完整预留资金与槽位</li>
 *   <li>槽位之间不自动调拨</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockPortfolioService {
    /**
     * 组合编码 - 正式VIP组合
     */
    public static final String PORTFOLIO_CODE = "VIP_FORMAL";
    /**
     * 槽位数量
     */
    public static final int SLOT_COUNT = 5;
    /**
     * 每槽初始资金(20亿)
     */
    public static final BigDecimal INITIAL_CASH = new BigDecimal("2000000000.00");
    /**
     * 初始资金明文(仅用于Javadoc展示)
     */
    static final String INITIAL_CASH_PLAIN = "2,000,000,000.00";
    /**
     * 卖出费率(0.1%手续费,实得99.9%)
     */
    public static final BigDecimal SELL_FEE_RATE = new BigDecimal("0.999");
    /**
     * 卖出费率明文(仅用于Javadoc展示)
     */
    static final String SELL_FEE_RATE_TEXT = "0.1%";
    /**
     * 入场价格偏离阈值(0.15%),仅向上偏离超过此值时取消
     */
    public static final BigDecimal ENTRY_DEVIATION_THRESHOLD = new BigDecimal("0.0015");
    /**
     * 最长持有天数
     */
    public static final int MAX_HOLD_DAYS = 14;
    /**
     * 金额与收益率计算精度
     */
    private static final int MATH_SCALE = 18;
    /**
     * 入场过期容错分钟数(staleAt = signalBarStart + 30min窗口 + 5min容错)
     */
    private static final int ENTRY_STALE_GRACE_MINUTES = 35;
    /**
     * 槽位非空校验提示信息
     */
    private static final String SLOT_NULL_MSG = "槽位不能为空";

    private final TornStockPortfolioSlotDAO portfolioSlotDAO;

    // ==================== 槽位查询 ====================

    /**
     * 查找首个可用槽位(slotStatus == AVAILABLE)
     * <p>
     * 按槽位序号升序遍历正式组合的全部槽位,返回第一个状态为 AVAILABLE 的槽位。
     * 若全部槽位均被占用/预留/陈旧,则返回 {@link Optional#empty()}。
     *
     * @return 首个可用槽位;无可用槽位时返回empty
     */
    public Optional<TornStockPortfolioSlotDO> findAvailableSlot() {
        List<TornStockPortfolioSlotDO> slots = portfolioSlotDAO.selectAllByPortfolioCode(PORTFOLIO_CODE);
        return slots.stream()
                .filter(slot -> StockSlotStatusEnum.AVAILABLE.getCode().equals(slot.getSlotStatus()))
                .findFirst();
    }

    // ==================== 槽位生命周期 ====================

    /**
     * 预留槽位和预算(ENTRY_PENDING阶段)
     * <p>
     * 将槽位状态置为 RESERVED,从可用现金中锁定预留资金至预留现金,绑定当前批次ID。
     * 首期每槽单批次预留,正常情况下预留全部可用现金。
     *
     * @param slot           目标槽位(应为AVAILABLE状态)
     * @param reservedAmount 预留金额(>0)
     * @param batchId        关联批次ID
     */
    public void reserveSlot(TornStockPortfolioSlotDO slot, BigDecimal reservedAmount, Long batchId) {
        Objects.requireNonNull(slot, SLOT_NULL_MSG);
        Objects.requireNonNull(reservedAmount, "预留金额不能为空");
        Objects.requireNonNull(batchId, "批次ID不能为空");

        BigDecimal currentAvailable = slot.getAvailableCash() == null ? BigDecimal.ZERO : slot.getAvailableCash();
        BigDecimal currentReserved = slot.getReservedCash() == null ? BigDecimal.ZERO : slot.getReservedCash();
        slot.setAvailableCash(currentAvailable.subtract(reservedAmount));
        slot.setReservedCash(currentReserved.add(reservedAmount));
        slot.setCurrentBatchId(batchId);
        slot.setSlotStatus(StockSlotStatusEnum.RESERVED.getCode());
        log.debug("槽位[{}]预留金额{},绑定批次{},可用余额{}", slot.getSlotNo(), reservedAmount, batchId, slot.getAvailableCash());
    }

    /**
     * 建仓占用槽位
     * <p>
     * 计算实际成本(actualCost = quantity × entryReferencePrice),从预留资金中扣除,
     * 余款(remainingCash = reservedCash - actualCost)转为可用现金,清零预留资金,
     * 槽位状态置为 OCCUPIED,绑定批次ID。余款保留在原槽实现槽内复利。
     *
     * @param slot                目标槽位(应为RESERVED状态)
     * @param quantity            买入股数(整数)
     * @param entryReferencePrice 入场参考价
     * @param batchId             关联批次ID
     */
    public void occupySlot(TornStockPortfolioSlotDO slot, Long quantity, BigDecimal entryReferencePrice, Long batchId) {
        Objects.requireNonNull(slot, SLOT_NULL_MSG);
        Objects.requireNonNull(quantity, "股数不能为空");
        Objects.requireNonNull(entryReferencePrice, "入场参考价不能为空");
        Objects.requireNonNull(batchId, "批次ID不能为空");

        BigDecimal actualCost = entryReferencePrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal currentReserved = slot.getReservedCash() == null ? BigDecimal.ZERO : slot.getReservedCash();
        BigDecimal remainingCash = currentReserved.subtract(actualCost);
        slot.setAvailableCash(remainingCash);
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setCurrentBatchId(batchId);
        slot.setSlotStatus(StockSlotStatusEnum.OCCUPIED.getCode());
        log.debug("槽位[{}]建仓占用,股数{},实际成本{},余款{},批次{}", slot.getSlotNo(), quantity, actualCost, remainingCash, batchId);
    }

    /**
     * 释放槽位(取消待买候选时调用)
     * <p>
     * 将预留资金全额退回可用现金,清零预留资金,槽位状态置为 AVAILABLE,
     * 解绑当前批次ID。适用于 ENTRY_PENDING 被取消的场景。
     *
     * @param slot 目标槽位(应为RESERVED状态)
     */
    public void releaseSlot(TornStockPortfolioSlotDO slot) {
        Objects.requireNonNull(slot, SLOT_NULL_MSG);

        BigDecimal currentReserved = slot.getReservedCash() == null ? BigDecimal.ZERO : slot.getReservedCash();
        BigDecimal currentAvailable = slot.getAvailableCash() == null ? BigDecimal.ZERO : slot.getAvailableCash();
        slot.setAvailableCash(currentAvailable.add(currentReserved));
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setCurrentBatchId(null);
        slot.setSlotStatus(StockSlotStatusEnum.AVAILABLE.getCode());
        log.debug("槽位[{}]释放,退回预留资金{}", slot.getSlotNo(), currentReserved);
    }

    /**
     * 卖出结算
     * <p>
     * 计算卖出所得(sellProceeds = quantity × exitReferencePrice × 0.999,扣除0.1%手续费),
     * 回笼到原槽可用现金(与建仓余款累加),槽位状态置为 AVAILABLE,解绑当前批次ID,实现槽内复利。
     *
     * @param slot               目标槽位(应为OCCUPIED或EXIT_PENDING关联状态)
     * @param quantity           卖出股数(整数)
     * @param exitReferencePrice 卖出参考价
     */
    public void settleSlot(TornStockPortfolioSlotDO slot, Long quantity, BigDecimal exitReferencePrice) {
        Objects.requireNonNull(slot, SLOT_NULL_MSG);
        Objects.requireNonNull(quantity, "股数不能为空");
        Objects.requireNonNull(exitReferencePrice, "卖出参考价不能为空");

        BigDecimal sellProceeds = exitReferencePrice
                .multiply(BigDecimal.valueOf(quantity))
                .multiply(SELL_FEE_RATE);
        BigDecimal currentAvailable = slot.getAvailableCash() == null ? BigDecimal.ZERO : slot.getAvailableCash();
        slot.setAvailableCash(currentAvailable.add(sellProceeds));
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setCurrentBatchId(null);
        slot.setSlotStatus(StockSlotStatusEnum.AVAILABLE.getCode());
        log.debug("槽位[{}]卖出结算,股数{},卖出所得{},可用余额{}", slot.getSlotNo(), quantity, sellProceeds, slot.getAvailableCash());
    }

    // ==================== 纯计算方法(静态) ====================

    /**
     * 计算整数股数
     * <p>
     * quantity = floor(availableCash / entryReferencePrice),向下取整保证不超过可用资金。
     *
     * @param availableCash       可用资金
     * @param entryReferencePrice 入场参考价(>0)
     * @return 整数股数;可用资金不足买入1股或价格为非正数时返回0
     */
    public static Long calculateQuantity(BigDecimal availableCash, BigDecimal entryReferencePrice) {
        if (availableCash == null || entryReferencePrice == null
                || availableCash.signum() <= 0 || entryReferencePrice.signum() <= 0) {
            return 0L;
        }
        BigDecimal rawQuotient = availableCash.divide(entryReferencePrice, MATH_SCALE, RoundingMode.HALF_UP);
        return rawQuotient.setScale(0, RoundingMode.DOWN).longValueExact();
    }

    /**
     * 计算扣费后净收益率(静态)
     * <p>
     * netReturn = exitReferencePrice / entryReferencePrice × 0.999 - 1,
     * 统一扣除0.1%卖出手续费。所有群消息、账本、收益统计和关闭判断均使用此净收益。
     *
     * @param entryReferencePrice 入场参考价(>0)
     * @param exitReferencePrice  卖出参考价
     * @return 净收益率(如0.008表示+0.8%);入场价为非正数时返回null
     */
    public static BigDecimal calculateNetReturn(BigDecimal entryReferencePrice, BigDecimal exitReferencePrice) {
        if (entryReferencePrice == null || exitReferencePrice == null
                || entryReferencePrice.signum() <= 0) {
            return null;
        }
        return exitReferencePrice
                .divide(entryReferencePrice, MATH_SCALE, RoundingMode.HALF_UP)
                .multiply(SELL_FEE_RATE)
                .subtract(BigDecimal.ONE);
    }

    /**
     * 计算组合权益
     * <p>
     * equity = sum(availableCash + reservedCash) + sum(batchMarketValues)
     * <br>可用现金 + 待买预留现金 + 开放仓位按当前实际价格计算的扣费后市值。
     *
     * @param slots             全部槽位列表
     * @param batchMarketValues 开放仓位市值映射(key=槽位ID, value=扣费后市值);无开放仓位时传空Map
     * @return 组合权益总额;入参为空时返回0
     */
    public BigDecimal calculateEquity(List<TornStockPortfolioSlotDO> slots, Map<Long, BigDecimal> batchMarketValues) {
        if (slots == null || slots.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal cashEquity = slots.stream()
                .map(slot -> {
                    BigDecimal available = slot.getAvailableCash() == null ? BigDecimal.ZERO : slot.getAvailableCash();
                    BigDecimal reserved = slot.getReservedCash() == null ? BigDecimal.ZERO : slot.getReservedCash();
                    return available.add(reserved);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal positionEquity = BigDecimal.ZERO;
        if (batchMarketValues != null && !batchMarketValues.isEmpty()) {
            positionEquity = batchMarketValues.values().stream()
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return cashEquity.add(positionEquity);
    }

    /**
     * 检查入场价格偏离(静态)
     * <p>
     * entryDeviation = entryReferencePrice / signalReferencePrice - 1,
     * 仅向上偏离超过 {@link #ENTRY_DEVIATION_THRESHOLD}(0.15%)时返回true,应取消批次。
     * <br>恰好0.15%不取消;价格相同不取消;价格下跌不因偏离取消。
     *
     * @param signalReferencePrice 信号参考价(>0)
     * @param entryReferencePrice  实际入场参考价
     * @return true表示偏离超限应取消;false表示可继续入场。信号价为非正数时返回false(不取消)
     */
    public static boolean checkEntryPriceDeviation(BigDecimal signalReferencePrice, BigDecimal entryReferencePrice) {
        if (signalReferencePrice == null || entryReferencePrice == null
                || signalReferencePrice.signum() <= 0) {
            return false;
        }
        BigDecimal entryDeviation = entryReferencePrice
                .divide(signalReferencePrice, MATH_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
        return entryDeviation.compareTo(ENTRY_DEVIATION_THRESHOLD) > 0;
    }

    /**
     * 计算入场过期时间(静态)
     * <p>
     * staleAt = signalBarStart + 35分钟(30分钟预期入场窗口 + 5分钟容错)。
     * 超过此时间仍未成功入场的 ENTRY_PENDING 批次应取消(reason = ENTRY_DATA_STALE)。
     *
     * @param signalBarStart 信号bar起始时间
     * @return 入场过期时间;入参为空时返回null
     */
    public static LocalDateTime calculateEntryStaleAt(LocalDateTime signalBarStart) {
        if (signalBarStart == null) {
            return null;
        }
        return signalBarStart.plusMinutes(ENTRY_STALE_GRACE_MINUTES);
    }

    /**
     * 将槽位列表按ID索引为映射,避免多处重复代码
     *
     * @param slots 槽位列表
     * @return 按槽位ID索引的映射;入参为null时返回空映射
     */
    public static Map<Long, TornStockPortfolioSlotDO> indexSlotsById(List<TornStockPortfolioSlotDO> slots) {
        Map<Long, TornStockPortfolioSlotDO> map = new HashMap<>();
        if (slots == null) {
            return map;
        }
        for (TornStockPortfolioSlotDO slot : slots) {
            if (slot.getId() != null) {
                map.put(slot.getId(), slot);
            }
        }
        return map;
    }
}
