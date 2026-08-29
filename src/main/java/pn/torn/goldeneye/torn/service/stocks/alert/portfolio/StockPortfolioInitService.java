package pn.torn.goldeneye.torn.service.stocks.alert.portfolio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.IntStream;

/**
 * 股票组合初始化服务 - 验证并补救正式与候选影子两个VIP组合的5个20亿槽位
 * <p>
 * 正式组合({@link StockPortfolioService#PORTFOLIO_CODE})与候选影子组合
 * ({@link StockPortfolioService#SHADOW_CANDIDATE_PORTFOLIO_CODE})各自由
 * {@value pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService#SLOT_COUNT} 个
 * 独立槽位组成,每槽初始资金 {@value pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService#INITIAL_CASH_PLAIN} 。
 * 本服务在应用启动或运维校验场景下,检查两个组合槽位的完整性与金额正确性:
 * 槽位缺失时按标准参数补建,初始资金或账本口径异常时记录警告但不擅自修改业务数据。
 *
 * <h3>核心规则</h3>
 * <ul>
 *   <li>槽位数量不足 {@value pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService#SLOT_COUNT} 时补建缺失槽位</li>
 *   <li>槽位全部缺失时一次性创建5个标准初始槽位</li>
 *   <li>补建使用 {@code INSERT ... ON CONFLICT (portfolio_code, slot_no) WHERE deleted = 0 DO NOTHING}
 *       与Liquibase迁移并发收敛: 与迁移已插入槽位冲突的行被静默忽略,不抛重复键异常(fail-closed)</li>
 *   <li>补建后重新查询并校验槽位序号1~5全部存在,任一仍缺失则返回false,不宣称修复成功</li>
 *   <li>已存在且状态正确的槽位不做任何修改</li>
 *   <li>initialCash 不等于20亿时仅记录警告,不自动修正(避免覆盖人工调拨)</li>
 *   <li>空闲槽位仅校验无预留、无绑定批次与非负可用资金,允许槽内复利后的动态权益</li>
 *   <li>预留与已持仓槽位校验关联批次、组合账本、槽位绑定和状态对应资金口径</li>
 * </ul>
 *
 * @author Bai
 * @version 1.5.1
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
     * 初始资金差额容差(用于BigDecimal比较,避免scale差异导致误报)
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
    private final TornStockVirtualBatchDAO virtualBatchDAO;

    // ==================== 槽位验证与初始化 ====================

    /**
     * 验证并初始化正式与候选影子两个组合的5个20亿槽位
     * <p>
     * 对每个组合编码查询全部槽位,按以下顺序处理:
     * <ol>
     *   <li>槽位全部缺失:一次性创建5个标准初始槽位</li>
     *   <li>槽位数量不足:补建缺失序号的槽位</li>
     *   <li>逐个验证已存在槽位的initialCash与状态对应的资金账本口径,异常时记录警告</li>
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
     * <p>
     * 槽位全部缺失或数量不足时先补建,补建后重新查询并校验1~5槽位齐全
     * (fail-closed: 补建后仍缺失则返回false,不宣称修复成功)。
     * 缺失槽位计算与补建委托 {@link #collectMissingSlotNos(List, String)}。
     *
     * @param portfolioCode 组合编码
     * @return true表示该组合槽位完整且金额校验全部通过
     */
    private boolean verifyAndInitPortfolio(String portfolioCode) {
        List<TornStockPortfolioSlotDO> slots = portfolioSlotDAO.selectAllByPortfolioCode(portfolioCode);

        List<Integer> missingSlotNos = collectMissingSlotNos(slots, portfolioCode);
        boolean repaired = !missingSlotNos.isEmpty();
        if (repaired) {
            log.info("组合[{}]槽位补建完成,本次共补建{}个槽位",
                    portfolioCode, missingSlotNos.size());
            if (!verifyCompleteAfterRepair(portfolioCode)) {
                log.error("组合[{}]修复后槽位仍不完整(slot_no 1~{}必须全部存在),拒绝判定成功",
                        portfolioCode, StockPortfolioService.SLOT_COUNT);
                return false;
            }
            return false;
        }

        return validateExistingSlots(slots);
    }

    /**
     * 计算指定组合缺失的槽位序号,并补建缺失槽位。
     * <p>
     * 槽位全部缺失时一次性创建1~5全部槽位;部分缺失时按序号逐个补建。
     *
     * @param slots         已查询到的槽位列表(可能为空)
     * @param portfolioCode 组合编码
     * @return 本次补建的缺失槽位序号列表;无缺失时返回空列表
     */
    private List<Integer> collectMissingSlotNos(List<TornStockPortfolioSlotDO> slots,
                                                String portfolioCode) {
        if (slots == null || slots.isEmpty()) {
            log.warn("组合[{}]槽位全部缺失,创建{}个标准初始槽位",
                    portfolioCode, StockPortfolioService.SLOT_COUNT);
            createSlots(portfolioCode, 1, StockPortfolioService.SLOT_COUNT);
            return IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                    .boxed()
                    .toList();
        }
        Set<Integer> existingSlotNos = collectExistingSlotNos(slots);
        List<Integer> missingSlotNos = IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                .filter(no -> !existingSlotNos.contains(no))
                .boxed()
                .toList();
        if (!missingSlotNos.isEmpty()) {
            log.warn("组合[{}]缺失槽位序号{},开始补建", portfolioCode, missingSlotNos);
            for (Integer slotNo : missingSlotNos) {
                createSlots(portfolioCode, slotNo, slotNo);
            }
        }
        return missingSlotNos;
    }

    /**
     * 收集指定槽位列表已存在的槽位序号集合。
     *
     * @param slots 槽位列表
     * @return 已存在槽位序号集合
     */
    private Set<Integer> collectExistingSlotNos(List<TornStockPortfolioSlotDO> slots) {
        Set<Integer> existingSlotNos = new HashSet<>();
        for (TornStockPortfolioSlotDO slot : slots) {
            if (slot.getSlotNo() != null) {
                existingSlotNos.add(slot.getSlotNo());
            }
        }
        return existingSlotNos;
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
     * lockVersion初始化为 {@value #INITIAL_LOCK_VERSION} 。使用冲突安全批量插入
     * {@code INSERT ... ON CONFLICT DO NOTHING},与Liquibase迁移并发时冲突行被忽略,
     * 返回实际插入行数并记录。
     *
     * @param portfolioCode 组合编码
     * @param fromNo        起始槽位序号(含)
     * @param toNo          结束槽位序号(含)
     */
    private void createSlots(String portfolioCode, int fromNo, int toNo) {
        List<TornStockPortfolioSlotDO> newSlots = IntStream.rangeClosed(fromNo, toNo)
                .mapToObj(slotNo -> buildStandardSlot(portfolioCode, slotNo))
                .toList();
        int inserted = portfolioSlotDAO.insertSlotsIgnoreConflict(newSlots);
        log.info("组合[{}]创建槽位序号{}~{}共{}个,本次实际插入{}个,每个初始资金{}",
                portfolioCode, fromNo, toNo, newSlots.size(), inserted,
                StockPortfolioService.INITIAL_CASH);
    }

    /**
     * 补建后重新查询指定组合槽位,校验槽位序号1~5是否全部存在。
     * <p>
     * fail-closed复查: 补建插入可能与Liquibase迁移并发被部分冲突吸收,插入返回数不能证明收敛,
     * 必须重新查询数据库确认每个槽位序号均存在。
     *
     * @param portfolioCode 组合编码
     * @return true表示该组合当前存在全部5个有效槽位
     */
    private boolean verifyCompleteAfterRepair(String portfolioCode) {
        List<TornStockPortfolioSlotDO> slots = portfolioSlotDAO.selectAllByPortfolioCode(portfolioCode);
        if (slots == null || slots.isEmpty()) {
            return false;
        }
        Set<Integer> existingSlotNos = collectExistingSlotNos(slots);
        return IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                .allMatch(existingSlotNos::contains);
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
     * 验证已存在槽位的初始资金与状态对应的账本正确性。
     * <p>
     * 逐个槽位检查:
     * <ul>
     *   <li>initialCash 是否等于 {@link StockPortfolioService#INITIAL_CASH} (容差 {@link #CASH_TOLERANCE} )</li>
     *   <li>AVAILABLE: 无预留、无绑定批次且可用资金非负,允许与initialCash不同</li>
     *   <li>RESERVED: 关联同组合ENTRY_PENDING批次、槽位绑定与预留金额有效性</li>
     *   <li>OCCUPIED/STALE: 关联批次、组合账本、槽位绑定、reservedCash与remainingCash是否一致</li>
     * </ul>
     * 任意项不匹配时记录警告但不修改数据。所有槽位均通过时返回true。
     *
     * @param slots 已存在的槽位列表
     * @return true表示全部槽位金额校验通过;false表示存在告警
     */
    private boolean validateExistingSlots(List<TornStockPortfolioSlotDO> slots) {
        Map<Long, TornStockVirtualBatchDO> batchById = loadBoundBatchesById(slots);
        boolean allValid = true;
        for (TornStockPortfolioSlotDO slot : slots) {
            allValid = validateInitialCash(slot) && allValid;
            TornStockVirtualBatchDO batch = slot.getCurrentBatchId() == null
                    ? null : batchById.get(slot.getCurrentBatchId());
            allValid = validateSlotLedger(slot, batch) && allValid;
        }
        return allValid;
    }

    /**
     * 批量加载所有绑定批次，避免按槽位逐条查询。
     *
     * @param slots 已存在槽位列表
     * @return 批次ID到批次的映射
     */
    private Map<Long, TornStockVirtualBatchDO> loadBoundBatchesById(List<TornStockPortfolioSlotDO> slots) {
        List<Long> batchIds = new ArrayList<>();
        for (TornStockPortfolioSlotDO slot : slots) {
            if (slot.getCurrentBatchId() != null) {
                batchIds.add(slot.getCurrentBatchId());
            }
        }
        if (batchIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, TornStockVirtualBatchDO> batchById = new HashMap<>();
        List<TornStockVirtualBatchDO> batches = virtualBatchDAO.listByIds(batchIds.stream().distinct().toList());
        if (batches == null) {
            return batchById;
        }
        for (TornStockVirtualBatchDO batch : batches) {
            if (batch != null && batch.getId() != null) {
                batchById.putIfAbsent(batch.getId(), batch);
            }
        }
        return batchById;
    }

    /**
     * 校验槽位初始资金是否仍为标准资金。
     *
     * @param slot 待校验槽位
     * @return 初始资金正确返回true
     */
    private boolean validateInitialCash(TornStockPortfolioSlotDO slot) {
        BigDecimal initialCash = slot.getInitialCash();
        if (initialCash != null
                && initialCash.subtract(StockPortfolioService.INITIAL_CASH).abs()
                .compareTo(CASH_TOLERANCE) <= 0) {
            return true;
        }
        log.warn("槽位[{}]initialCash={}与标准值{}不匹配,请人工核查",
                slot.getSlotNo(), initialCash, StockPortfolioService.INITIAL_CASH);
        return false;
    }

    /**
     * 按槽位状态选择现金或持仓账本校验。
     *
     * @param slot  待校验槽位
     * @param batch 该槽位绑定的批次；非持仓槽位为null
     * @return 账本一致返回true
     */
    private boolean validateSlotLedger(TornStockPortfolioSlotDO slot, TornStockVirtualBatchDO batch) {
        if (StockSlotStatusEnum.AVAILABLE.getCode().equals(slot.getSlotStatus())) {
            return validateAvailableSlot(slot);
        }
        if (StockSlotStatusEnum.RESERVED.getCode().equals(slot.getSlotStatus())) {
            return validateReservedSlot(slot, batch);
        }
        if (isPositionSlot(slot)) {
            return validatePositionSlot(slot, batch);
        }
        log.warn("槽位[{}]状态{}无法识别,拒绝通过启动期账本校验",
                slot.getSlotNo(), slot.getSlotStatus());
        return false;
    }

    /**
     * 校验预留槽位与同组合待入场批次的一致性。
     *
     * @param slot  已预留槽位
     * @param batch 按currentBatchId加载的关联批次
     * @return 槽位与待入场批次账本一致时返回true
     */
    private boolean validateReservedSlot(TornStockPortfolioSlotDO slot, TornStockVirtualBatchDO batch) {
        if (!hasExpectedLedger(slot, batch)
                || !StockBatchStatusEnum.ENTRY_PENDING.getCode().equals(batch.getBatchStatus())) {
            log.warn("槽位[{}]预留批次账本或状态异常, portfolioCode={}, batchId={}, ledgerType={}, batchStatus={}",
                    slot.getSlotNo(), slot.getPortfolioCode(), slot.getCurrentBatchId(),
                    batch == null ? null : batch.getLedgerType(), batch == null ? null : batch.getBatchStatus());
            return false;
        }
        if (!hasMatchingSlotBinding(slot, batch)) {
            log.warn("槽位[{}]与预留批次绑定不一致, slotId={}, batchId={}, batchSlotId={}, batchSlotNo={}",
                    slot.getSlotNo(), slot.getId(), batch.getId(), batch.getSlotId(), batch.getSlotNo());
            return false;
        }
        if (!isNonNegative(slot.getAvailableCash()) || !isPositive(slot.getReservedCash())) {
            log.warn("槽位[{}]预留资金非法, availableCash={}, reservedCash={}, batchId={}",
                    slot.getSlotNo(), slot.getAvailableCash(), slot.getReservedCash(), batch.getId());
            return false;
        }
        return true;
    }

    /**
     * 校验空闲槽位无在途批次与预留资金，且可用资金为非负值。
     *
     * @param slot 待校验槽位
     * @return 槽位空闲账本一致时返回true
     */
    private boolean validateAvailableSlot(TornStockPortfolioSlotDO slot) {
        if (slot.getCurrentBatchId() == null
                && isZero(slot.getReservedCash())
                && isNonNegative(slot.getAvailableCash())) {
            return true;
        }
        log.warn("槽位[{}]空闲账本不一致, availableCash={}, reservedCash={}, currentBatchId={},请人工核查",
                slot.getSlotNo(), slot.getAvailableCash(), slot.getReservedCash(), slot.getCurrentBatchId());
        return false;
    }

    /**
     * 校验已持仓或数据陈旧槽位与关联开放批次的一致性。
     *
     * @param slot  已持仓或数据陈旧槽位
     * @param batch 按currentBatchId加载的关联批次
     * @return 槽位与批次账本一致时返回true
     */
    private boolean validatePositionSlot(TornStockPortfolioSlotDO slot, TornStockVirtualBatchDO batch) {
        if (!hasExpectedLedger(slot, batch) || !isOpenPositionBatch(batch)) {
            log.warn("槽位[{}]关联批次账本或状态异常, portfolioCode={}, batchId={}, ledgerType={}, batchStatus={}",
                    slot.getSlotNo(), slot.getPortfolioCode(), slot.getCurrentBatchId(),
                    batch == null ? null : batch.getLedgerType(), batch == null ? null : batch.getBatchStatus());
            return false;
        }
        if (!hasMatchingSlotBinding(slot, batch)) {
            log.warn("槽位[{}]与关联批次绑定不一致, slotId={}, batchId={}, batchSlotId={}, batchSlotNo={}",
                    slot.getSlotNo(), slot.getId(), batch.getId(), batch.getSlotId(), batch.getSlotNo());
            return false;
        }
        if (!isNonNegative(slot.getAvailableCash())
                || !isZero(slot.getReservedCash())
                || batch.getRemainingCash() == null
                || slot.getAvailableCash().compareTo(batch.getRemainingCash()) != 0) {
            log.warn("槽位[{}]持仓现金与批次余款不一致, availableCash={}, reservedCash={}, batchRemainingCash={}, batchId={}",
                    slot.getSlotNo(), slot.getAvailableCash(), slot.getReservedCash(), batch.getRemainingCash(), batch.getId());
            return false;
        }
        return true;
    }

    /**
     * 判断关联批次是否属于当前槽位组合、未逻辑删除且批次ID与槽位绑定一致。
     *
     * @param slot  槽位
     * @param batch 关联批次
     * @return 批次账本类型、逻辑删除标识与批次ID均匹配返回true
     */
    private boolean hasExpectedLedger(TornStockPortfolioSlotDO slot, TornStockVirtualBatchDO batch) {
        return batch != null
                && slot.getCurrentBatchId() != null
                && slot.getCurrentBatchId().equals(batch.getId())
                && Integer.valueOf(0).equals(batch.getDeleted())
                && expectedLedgerType(slot.getPortfolioCode()).equals(batch.getLedgerType());
    }

    /**
     * 判断槽位与关联批次的槽位ID、编号是否双向一致。
     *
     * @param slot  槽位
     * @param batch 关联批次
     * @return 槽位ID和槽位编号均一致时返回true
     */
    private boolean hasMatchingSlotBinding(TornStockPortfolioSlotDO slot, TornStockVirtualBatchDO batch) {
        return slot.getId() != null
                && slot.getId().equals(batch.getSlotId())
                && slot.getSlotNo() != null
                && slot.getSlotNo().equals(batch.getSlotNo());
    }

    /**
     * 获取组合对应的唯一槽位账本类型。
     *
     * @param portfolioCode 槽位组合编码
     * @return 对应槽位账本类型;未知组合返回空字符串
     */
    private String expectedLedgerType(String portfolioCode) {
        if (StockPortfolioService.PORTFOLIO_CODE.equals(portfolioCode)) {
            return StockLedgerTypeEnum.FORMAL.getCode();
        }
        if (StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE.equals(portfolioCode)) {
            return StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode();
        }
        return "";
    }

    /**
     * 判断批次是否已形成持仓，仍持续占用槽位。
     *
     * @param batch 待判断批次
     * @return 持仓、数据陈旧或待卖出批次返回true
     */
    private boolean isOpenPositionBatch(TornStockVirtualBatchDO batch) {
        String batchStatus = batch.getBatchStatus();
        return StockBatchStatusEnum.OPEN.getCode().equals(batchStatus)
                || StockBatchStatusEnum.DATA_STALE.getCode().equals(batchStatus)
                || StockBatchStatusEnum.EXIT_PENDING.getCode().equals(batchStatus)
                || StockBatchStatusEnum.DATA_STALE_EXIT.getCode().equals(batchStatus);
    }

    /**
     * 判断槽位是否已持仓或因数据陈旧仍持续占用。
     *
     * @param slot 待判断槽位
     * @return 已持仓或数据陈旧占用时返回true
     */
    private boolean isPositionSlot(TornStockPortfolioSlotDO slot) {
        String slotStatus = slot.getSlotStatus();
        return StockSlotStatusEnum.OCCUPIED.getCode().equals(slotStatus)
                || StockSlotStatusEnum.STALE.getCode().equals(slotStatus);
    }

    /**
     * 判断金额是否为零。
     *
     * @param cash 资金字段
     * @return 非空且数值为零时返回true
     */
    private boolean isZero(BigDecimal cash) {
        return cash != null && cash.compareTo(ZERO_RESERVED) == 0;
    }

    /**
     * 判断金额是否为非负值。
     *
     * @param cash 资金字段
     * @return 非空且大于等于零时返回true
     */
    private boolean isNonNegative(BigDecimal cash) {
        return cash != null && cash.signum() >= 0;
    }

    /**
     * 判断金额是否为正值。
     *
     * @param cash 资金字段
     * @return 非空且大于零时返回true
     */
    private boolean isPositive(BigDecimal cash) {
        return cash != null && cash.signum() > 0;
    }
}
