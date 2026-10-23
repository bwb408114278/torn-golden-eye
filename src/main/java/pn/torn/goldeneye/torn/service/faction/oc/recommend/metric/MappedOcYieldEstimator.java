package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcPlanningManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendTunings;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 等级映射产出估算器。
 *
 * <p>没有足够实测样本的OC（含从未进过结算池的OC）由本实现承接，因此「新OC能不能被推荐」
 * 不再取决于它是否被人工加进大锅饭名单。映射口径固定为业务基线的期望口径（口径B）：
 * 优先取torn_setting_oc.expected_reward（该字段本身已是「成功时奖励乘以成功率」），
 * 缺失或为0时退回等级阶梯的「成功时平均奖励乘以成功率」。</p>
 *
 * <p>链式OC按整条链估算：奖励只取叶子，人天累加全部步骤；叶子的期望值再乘以前置步骤成功率，
 * 等价于「奖励只在叶子成功时结算」，避免把多步链的产出高估。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class MappedOcYieldEstimator implements OcYieldEstimator {
    private final TornSettingOcManager settingOcManager;
    private final TornSettingOcPlanningManager planningManager;
    private final OcChainValueReducer<OcChainStep> chainValueReducer = new OcChainValueReducer<>();

    @Override
    public boolean supports(OcYieldSourceEnum source) {
        return source == OcYieldSourceEnum.MAPPED || source == OcYieldSourceEnum.CHAIN_MAPPED;
    }

    @Override
    public OcYieldEstimate estimate(TornFactionOcDO oc, LocalDateTime snapshotTime) {
        List<OcChainStep> steps = resolveChainSteps(oc);
        int leafPosition = steps.size() - 1;
        BigDecimal prefixRate = steps.size() > 1 ? prefixSuccessRate(steps) : BigDecimal.ONE;
        OcYieldSourceEnum source = steps.size() > 1
                ? OcYieldSourceEnum.CHAIN_MAPPED : OcYieldSourceEnum.MAPPED;
        return chainValueReducer.reduce(steps,
                step -> stepEstimate(step, step.index() == leafPosition, prefixRate, source),
                OcChainStep::index);
    }

    /**
     * 判断该OC是否属于链式OC（配置中存在以它为前置或后继的链关系）。
     *
     * @param oc OC数据
     * @return 属于链式OC时返回true
     */
    public boolean isChainedOc(TornFactionOcDO oc) {
        return resolveChainSteps(oc).size() > 1;
    }

    /**
     * 汇总该OC所属整条链的折算信息，供实测来源补齐前置成功率与前置人天。
     *
     * <p>实测来源只能聚合出单个步骤的净收益，前置步骤虽然不付钱却真实占用人力，
     * 因此整链口径必须由本方法补齐，否则链式OC的单位人天产出会被高估一倍以上。</p>
     *
     * @param oc OC数据
     * @return 链折算信息；非链式OC返回不折算的单步信息
     */
    public OcChainSummary summarizeChain(TornFactionOcDO oc) {
        List<OcChainStep> steps = resolveChainSteps(oc);
        if (steps.size() <= 1) {
            return new OcChainSummary(false, BigDecimal.ONE, stepMemberDays(steps.getFirst()));
        }
        BigDecimal memberDays = steps.stream()
                .map(this::stepMemberDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OcChainSummary(true, prefixSuccessRate(steps), memberDays);
    }

    /**
     * 解析该OC所属的完整链步骤（根到叶子）；不属于链式OC时返回只含自身的单步。
     */
    private List<OcChainStep> resolveChainSteps(TornFactionOcDO oc) {
        List<TornSettingOcChainDO> edges = planningManager.getChains();
        String chainCode = edges.stream()
                .filter(edge -> matches(edge.getParentOcName(), edge.getParentRank(), oc)
                        || matches(edge.getChildOcName(), edge.getChildRank(), oc))
                .map(TornSettingOcChainDO::getChainCode)
                .findFirst()
                .orElse(null);
        if (chainCode == null) {
            return List.of(new OcChainStep(oc.getName(), oc.getRank(), 0));
        }

        List<TornSettingOcChainDO> chainEdges = edges.stream()
                .filter(edge -> Objects.equals(edge.getChainCode(), chainCode))
                .sorted(Comparator.comparing(TornSettingOcChainDO::getSequenceNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<OcChainStep> steps = new ArrayList<>(chainEdges.size() + 1);
        TornSettingOcChainDO first = chainEdges.getFirst();
        steps.add(new OcChainStep(first.getParentOcName(), first.getParentRank(), 0));
        for (TornSettingOcChainDO edge : chainEdges) {
            steps.add(new OcChainStep(edge.getChildOcName(), edge.getChildRank(), steps.size()));
        }
        return steps;
    }

    /**
     * 计算前置步骤成功率的乘积，用于把叶子期望值折算到整链口径。
     */
    private BigDecimal prefixSuccessRate(List<OcChainStep> steps) {
        BigDecimal rate = BigDecimal.ONE;
        for (OcChainStep step : steps.subList(0, steps.size() - 1)) {
            rate = rate.multiply(OcRecommendTunings.ladderSuccessRate(step.rank()));
        }
        return rate;
    }

    /**
     * 估算单个链步骤：只有叶子携带奖励，每个步骤都携带自身的人天。
     */
    private OcYieldEstimate stepEstimate(OcChainStep step, boolean leaf, BigDecimal prefixRate,
                                         OcYieldSourceEnum source) {
        BigDecimal reward = leaf ? expectedLeafReward(step).multiply(prefixRate) : BigDecimal.ZERO;
        return new OcYieldEstimate(reward, stepMemberDays(step), source);
    }

    /**
     * 估算叶子的期望奖励（口径B）：配置值优先，缺失时退回等级阶梯。
     */
    private BigDecimal expectedLeafReward(OcChainStep step) {
        TornSettingOcDO setting = findSetting(step.ocName(), step.rank());
        if (setting != null && setting.getExpectedReward() != null && setting.getExpectedReward() > 0) {
            return BigDecimal.valueOf(setting.getExpectedReward());
        }
        return OcRecommendTunings.ladderSuccessReward(step.rank())
                .multiply(OcRecommendTunings.ladderSuccessRate(step.rank()));
    }

    /**
     * 计算单个步骤的期望占用人天：单岗位期望占用天数乘以岗位人数。
     */
    private BigDecimal stepMemberDays(OcChainStep step) {
        TornSettingOcDO setting = findSetting(step.ocName(), step.rank());
        int members = setting == null || setting.getRequiredMembers() == null
                ? 0 : setting.getRequiredMembers();
        return OcRecommendTunings.MEMBER_DAYS_PER_SLOT.multiply(BigDecimal.valueOf(Math.max(1, members)));
    }

    /**
     * 查询OC目录配置。
     */
    private TornSettingOcDO findSetting(String ocName, Integer rank) {
        return settingOcManager.getList().stream()
                .filter(setting -> Objects.equals(setting.getOcName(), ocName))
                .filter(setting -> Objects.equals(setting.getRank(), rank))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断配置行是否为指定OC。
     */
    private boolean matches(String ocName, Integer rank, TornFactionOcDO oc) {
        return Objects.equals(ocName, oc.getName()) && Objects.equals(rank, oc.getRank());
    }

    /**
     * 整条链的折算信息。
     *
     * @param chained           是否链式OC
     * @param prefixSuccessRate 前置步骤成功率乘积，非链式为1
     * @param memberDays        整条链的期望占用总人天
     * @author Bai
     * @version 1.9.3
     * @since 2026.10.23
     */
    public record OcChainSummary(
            boolean chained,
            BigDecimal prefixSuccessRate,
            BigDecimal memberDays) {
    }

    /**
     * 链步骤：名称与等级足以定位配置，顺序号用于确定叶子。
     *
     * @param ocName 步骤OC名称
     * @param rank   步骤OC等级
     * @param index  在链中的顺序号，根为0，叶子最大
     * @author Bai
     * @version 1.9.3
     * @since 2026.10.23
     */
    private record OcChainStep(
            String ocName,
            Integer rank,
            int index) {
    }
}
