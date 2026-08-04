package pn.torn.goldeneye.torn.service.faction.oc.income;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeSummaryDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcChainDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcCoefficientDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcIncomeSummaryDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcCoefficientDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcCoefficientManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * OC收益数据库测试共享基类。
 *
 * <p>集中管理测试数据搭建与物理删除清理，避免各测试类重复复制SQL与辅助方法：所有测试数据
 * 一律通过DAO保存，只有清理（物理删除）、逻辑删除标记与环形引用这类MyBatis-Plus逻辑删除
 * 无法表达的边角数据使用JdbcTemplate，且集中在基类提供，测试类按开闭原则扩展基类复用。</p>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.04
 */
public abstract class TornOcIncomeDbTestSupport {
    @Autowired
    protected TornFactionOcDAO ocDao;
    @Autowired
    protected TornFactionOcSlotDAO ocSlotDao;
    @Autowired
    protected TornFactionOcIncomeDAO incomeDao;
    @Autowired
    protected TornFactionOcIncomeSummaryDAO incomeSummaryDao;
    @Autowired
    protected TornSettingOcChainDAO ocChainDao;
    @Autowired
    protected TornSettingOcCoefficientDAO coefficientDao;
    @Autowired
    protected TornSettingOcCoefficientManager coefficientManager;
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * 本测试创建的OC ID，供物理删除清理。
     */
    protected final List<Long> createdOcIds = new ArrayList<>();
    /**
     * 本测试创建的链配置编码，供清理。
     */
    protected final List<String> testChainCodes = new ArrayList<>();
    /**
     * 本测试创建的系数配置ID，供清理。
     */
    protected final List<Long> testCoefficientIds = new ArrayList<>();

    /**
     * 保存大锅饭名单并返回原值，供测试结束时恢复。
     *
     * @param factionId  帮派ID
     * @param ocNames    测试名单
     * @return 原名单，不存在返回{@code null}
     */
    protected List<String> saveRotationList(Long factionId, List<String> ocNames) {
        List<String> original = TornConstants.ROTATION_OC_NAME.get(factionId);
        TornConstants.ROTATION_OC_NAME.put(factionId, ocNames);
        return original;
    }

    /**
     * 恢复大锅饭名单：原本不存在则删除测试键，原本存在则恢复原值。
     *
     * @param factionId      帮派ID
     * @param originalRotation 原名单，{@code null}表示原本不存在
     */
    protected void restoreRotationList(Long factionId, List<String> originalRotation) {
        if (originalRotation == null) {
            TornConstants.ROTATION_OC_NAME.remove(factionId);
        } else {
            TornConstants.ROTATION_OC_NAME.put(factionId, originalRotation);
        }
    }

    /**
     * 通过DAO创建测试OC并记录ID。
     *
     * @param factionId    帮派ID
     * @param previousOcId 前置OC ID
     * @param name         OC名称
     * @param rank         OC等级
     * @param status       OC状态
     * @param executedTime 执行时间
     * @param rewardMoney  奖励金额
     * @return 创建的OC
     */
    protected TornFactionOcDO createOc(Long factionId, Long previousOcId, String name, Integer rank,
                                       TornOcStatusEnum status, LocalDateTime executedTime, Long rewardMoney) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setFactionId(factionId);
        oc.setPreviousOcId(previousOcId);
        oc.setName(name);
        oc.setRank(rank);
        oc.setStatus(status.getCode());
        oc.setExecutedTime(executedTime);
        oc.setRewardMoney(rewardMoney);
        ocDao.save(oc);
        createdOcIds.add(oc.getId());
        return oc;
    }

    /**
     * 通过DAO创建测试岗位。
     *
     * @param ocId       OC ID
     * @param userId     用户ID，可传{@code null}模拟未分配岗位
     * @param position   岗位编码
     * @param passRate   成功率
     * @param itemValue  道具价值
     * @return 创建的岗位
     */
    protected TornFactionOcSlotDO createSlot(Long ocId, Long userId, String position, Integer passRate,
                                             Long itemValue) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setUserId(userId);
        slot.setPosition(position);
        slot.setPassRate(passRate);
        slot.setOutcomeItemValue(itemValue);
        ocSlotDao.save(slot);
        return slot;
    }

    /**
     * 通过DAO插入一条已存在的income，模拟历史结算记录。
     *
     * @param oc          所属OC
     * @param userId      用户ID
     * @param position    岗位编码
     * @param isSuccess   是否成功
     * @param totalReward 总奖励
     */
    protected void insertIncome(TornFactionOcDO oc, Long userId, String position, boolean isSuccess,
                                Long totalReward) {
        TornFactionOcIncomeDO income = new TornFactionOcIncomeDO();
        income.setFactionId(oc.getFactionId());
        income.setOcId(oc.getId());
        income.setOcName(oc.getName());
        income.setRank(oc.getRank());
        income.setOcExecutedTime(oc.getExecutedTime());
        income.setUserId(userId);
        income.setPosition(position);
        income.setPassRate(60);
        income.setBaseWorkingHours(4);
        income.setCoefficient(BigDecimal.valueOf(15));
        income.setEffectiveWorkingHours(BigDecimal.valueOf(60));
        income.setIsSuccess(isSuccess);
        income.setTotalReward(totalReward);
        income.setItemCost(0L);
        income.setTotalItemCost(0L);
        income.setFinalIncome(totalReward);
        incomeDao.save(income);
    }

    /**
     * 通过DAO插入一条已存在的汇总记录，模拟历史汇总。
     *
     * @param userId    用户ID
     * @param factionId 帮派ID
     * @param yearMonth 年月
     */
    protected void insertSummary(Long userId, Long factionId, String yearMonth) {
        TornFactionOcIncomeSummaryDO summary = new TornFactionOcIncomeSummaryDO();
        summary.setUserId(userId);
        summary.setFactionId(factionId);
        summary.setYearMonth(yearMonth);
        summary.setIsSettled(false);
        summary.setTotalEffectiveHours(BigDecimal.ZERO);
        summary.setTotalItemCost(0L);
        summary.setTotalReward(0L);
        summary.setNetReward(0L);
        summary.setFinalIncome(0L);
        summary.setOcCount(0);
        summary.setSuccessOcCount(0);
        incomeSummaryDao.save(summary);
    }

    /**
     * 通过DAO插入一条测试系数配置（全局factionId=0），覆盖任意成功率区间。
     *
     * @param factionId 帮派ID，测试固定使用0表示全局
     * @param ocName    OC名称
     * @param rank      OC等级
     * @param slotCode  岗位编码
     * @param passRate  成功率
     */
    protected void insertCoefficient(Long factionId, String ocName, Integer rank, String slotCode,
                                     Integer passRate) {
        TornSettingOcCoefficientDO coefficient = new TornSettingOcCoefficientDO();
        coefficient.setFactionId(factionId);
        coefficient.setOcName(ocName);
        coefficient.setRank(rank);
        coefficient.setSlotCode(slotCode);
        coefficient.setPassRateMin(Math.max(0, passRate - 1));
        coefficient.setPassRateMax(100);
        coefficient.setCoefficient(BigDecimal.valueOf(10));
        coefficientDao.save(coefficient);
        testCoefficientIds.add(coefficient.getId());
    }

    /**
     * 通过DAO插入一条测试链配置，使用唯一链编码避免与唯一约束冲突。
     *
     * @param parentName 前置OC名称
     * @param parentRank 前置OC等级
     * @param childName  后继OC名称
     * @param childRank  后继OC等级
     * @param enabled    是否启用
     */
    protected void insertChainConfig(String parentName, Integer parentRank, String childName, Integer childRank,
                                     boolean enabled) {
        TornSettingOcChainDO chain = new TornSettingOcChainDO();
        chain.setChainCode("TEST_CHAIN_" + System.nanoTime());
        chain.setParentOcName(parentName);
        chain.setParentRank(parentRank);
        chain.setChildOcName(childName);
        chain.setChildRank(childRank);
        chain.setSequenceNo(1);
        chain.setEnabled(enabled);
        ocChainDao.save(chain);
        testChainCodes.add(chain.getChainCode());
    }

    /**
     * 物理删除本测试创建的OC、岗位与income。
     *
     * <p>必须使用物理删除：本测试不开启事务回滚，逻辑删除会残留{@code deleted=1}记录。</p>
     */
    protected void physicalDeleteCreatedOcs() {
        if (createdOcIds.isEmpty()) {
            return;
        }
        String ids = createdOcIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        jdbcTemplate.update("DELETE FROM torn_faction_oc_income WHERE oc_id IN (" + ids + ")");
        jdbcTemplate.update("DELETE FROM torn_faction_oc_slot WHERE oc_id IN (" + ids + ")");
        jdbcTemplate.update("DELETE FROM torn_faction_oc WHERE id IN (" + ids + ")");
        createdOcIds.clear();
    }

    /**
     * 物理删除测试帮派全部income与汇总。
     *
     * @param factionId 测试帮派ID
     */
    protected void physicalDeleteFactionIncomeAndSummary(Long factionId) {
        jdbcTemplate.update("DELETE FROM torn_faction_oc_income WHERE faction_id = ?", factionId);
        jdbcTemplate.update("DELETE FROM torn_faction_oc_income_summary WHERE faction_id = ?", factionId);
    }

    /**
     * 物理删除测试用户全部汇总。
     *
     * @param userId 测试用户ID
     */
    protected void physicalDeleteSummaryByUser(Long userId) {
        jdbcTemplate.update("DELETE FROM torn_faction_oc_income_summary WHERE user_id = ?", userId);
    }

    /**
     * 物理删除指定用户在多帮派、多月份下的全部汇总。
     *
     * @param userId     测试用户ID
     * @param factions   帮派ID集合
     * @param months     年月集合（yyyy-MM）
     */
    protected void physicalDeleteSummaryByUserMonths(Long userId, List<Long> factions, List<String> months) {
        String factionIn = factions.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        String monthIn = months.stream().map(m -> "'" + m + "'").reduce((a, b) -> a + "," + b).orElse("");
        jdbcTemplate.update("DELETE FROM torn_faction_oc_income_summary WHERE user_id = ? AND faction_id IN (" +
                factionIn + ") AND year_month IN (" + monthIn + ")", userId);
    }

    /**
     * 物理删除测试帮派的全部测试数据（income、汇总、岗位、OC）。
     *
     * <p>仅用于合成测试帮派，不得触碰正式帮派数据。</p>
     *
     * @param factionId 测试帮派ID
     */
    protected void physicalDeleteFactionAllData(Long factionId) {
        List<Long> ocIds = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, factionId)
                .list()
                .stream()
                .map(TornFactionOcDO::getId)
                .toList();
        physicalDeleteFactionIncomeAndSummary(factionId);
        if (!ocIds.isEmpty()) {
            String ids = ocIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            jdbcTemplate.update("DELETE FROM torn_faction_oc_slot WHERE oc_id IN (" + ids + ")");
        }
        jdbcTemplate.update("DELETE FROM torn_faction_oc WHERE faction_id = ?", factionId);
    }

    /**
     * 清理测试链配置与系数配置并刷新缓存。
     */
    protected void cleanupConfigsAndRefreshCache() {
        if (!testChainCodes.isEmpty()) {
            ocChainDao.lambdaUpdate().in(TornSettingOcChainDO::getChainCode, testChainCodes).remove();
            testChainCodes.clear();
        }
        if (!testCoefficientIds.isEmpty()) {
            coefficientDao.lambdaUpdate().in(TornSettingOcCoefficientDO::getId, testCoefficientIds).remove();
            testCoefficientIds.clear();
        }
        coefficientManager.refreshCache();
    }

    /**
     * 将指定income标记为逻辑删除。
     *
     * <p>MyBatis-Plus逻辑删除字段无法通过DAO直接赋值，只能用原生SQL模拟历史逻辑删除记录，
     * 用于验证已删除income不阻断重新计算。</p>
     *
     * @param ocId 目标OC ID
     */
    protected void markIncomeLogicalDeleted(Long ocId) {
        jdbcTemplate.update("UPDATE torn_faction_oc_income SET deleted = 1 WHERE oc_id = ?", ocId);
    }

    /**
     * 将指定OC标记为逻辑删除。
     *
     * <p>同{@link #markIncomeLogicalDeleted(Long)}，MyBatis-Plus无法通过DAO直接赋值逻辑删除字段。</p>
     *
     * @param ocId 目标OC ID
     */
    protected void markOcLogicalDeleted(Long ocId) {
        jdbcTemplate.update("UPDATE torn_faction_oc SET deleted = 1 WHERE id = ?", ocId);
    }

    /**
     * 通过DAO调整OC的前置OC，用于构造环形引用等边界数据。
     *
     * @param ocId         目标OC ID
     * @param previousOcId 新的前置OC ID
     */
    protected void updatePreviousOc(Long ocId, Long previousOcId) {
        ocDao.lambdaUpdate()
                .set(TornFactionOcDO::getPreviousOcId, previousOcId)
                .eq(TornFactionOcDO::getId, ocId)
                .update();
    }
}
