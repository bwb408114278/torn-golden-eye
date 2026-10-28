package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.model.faction.oc.OcIncomeYieldStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendTunings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * 实测产出估算器。
 *
 * <p>数据来源是torn_faction_oc_income：按(OC名称, 等级, 帮派)跨历史实例聚合，不按当前活跃oc_id查询——
 * 同名OC每次招募都会产生新的oc_id，活跃实例本身没有历史记录。聚合口径为「每次尝试的期望净收益」：
 * 成功实例的总奖励减去全部实例的道具成本，再除以实例数，因此失败样本以0收益计入分母，
 * 与业务基线的期望口径一致。final_income是链级净收益快照，同链每一行相同，不能当个人份额使用。</p>
 *
 * <p>本类只输出未收缩的实测值，样本量与等级映射值的可信度加权由
 * {@link OcYieldEstimatorRegistry}统一完成；判定只看是否有历史实例，不看是否在大锅饭名单内。</p>
 *
 * <p>人天口径：单岗位占用天数取{@link OcRecommendTunings#MEMBER_DAYS_PER_SLOT}，
 * 人数取「目录配置 > 该队实际岗位数 > 1人」，见{@link OcYieldContext#teamSize}。</p>
 *
 * @author Bai
 * @version 1.9.4
 * @since 2026.10.23
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class MeasuredOcYieldEstimator implements OcYieldEstimator {
    /**
     * 期望净产出的小数位数。
     */
    private static final int REWARD_SCALE = 2;

    private final TornFactionOcIncomeDAO ocIncomeDao;
    private final TornSettingOcManager settingOcManager;

    @Override
    public boolean supports(OcYieldSourceEnum source) {
        return source == OcYieldSourceEnum.MEASURED;
    }

    @Override
    public OcYieldEstimate estimate(TornFactionOcDO oc, OcYieldContext context) {
        OcIncomeYieldStatsDO stats = findStats(oc.getFactionId(), oc.getName(), oc.getRank());
        return estimate(oc, stats, context);
    }

    /**
     * 一次性加载一个帮派全部OC的实测产出统计，供批量估算避免逐个候选查库。
     *
     * @param factionId 帮派ID
     * @return 该帮派每个（OC名称，等级）的实测产出统计
     */
    public List<OcIncomeYieldStatsDO> loadStats(long factionId) {
        return ocIncomeDao.queryYieldStats(factionId);
    }

    /**
     * 查询单个OC的实测产出统计。
     *
     * @param factionId 帮派ID
     * @param ocName    OC名称
     * @param rank      OC等级
     * @return 实测产出统计；无历史实例时返回null
     */
    public OcIncomeYieldStatsDO findStats(long factionId, String ocName, Integer rank) {
        return loadStats(factionId).stream()
                .filter(row -> Objects.equals(row.getOcName(), ocName) && Objects.equals(row.getRank(), rank))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断该OC是否有可用的历史实例。
     *
     * @param stats 实测产出统计，可为空
     * @return 尝试次数大于0时返回true
     */
    public boolean hasHistory(OcIncomeYieldStatsDO stats) {
        return attemptsOf(stats) > 0;
    }

    /**
     * 取历史尝试次数，作为可信度加权的样本量。
     *
     * @param stats 实测产出统计，可为空
     * @return 尝试次数；无统计时返回0
     */
    public int attemptsOf(OcIncomeYieldStatsDO stats) {
        return stats == null || stats.getAttemptCount() == null ? 0 : stats.getAttemptCount();
    }

    /**
     * 用已加载的实测统计估算期望产出，避免重复查库。
     *
     * @param oc      OC数据
     * @param stats   该OC的实测产出统计，可为空
     * @param context 本次估算的只读上下文
     * @return 期望产出估算值；无历史实例时返回0产出
     */
    public OcYieldEstimate estimate(TornFactionOcDO oc, OcIncomeYieldStatsDO stats, OcYieldContext context) {
        int attempts = attemptsOf(stats);
        if (attempts <= 0) {
            return OcYieldEstimate.zero(OcYieldSourceEnum.MEASURED);
        }
        long netReward = stats.getNetRewardSum() == null ? 0L : stats.getNetRewardSum();
        BigDecimal expectedNetReward = BigDecimal.valueOf(netReward)
                .divide(BigDecimal.valueOf(attempts), REWARD_SCALE, RoundingMode.HALF_UP);
        return new OcYieldEstimate(expectedNetReward, expectedMemberDays(oc, context),
                OcYieldSourceEnum.MEASURED);
    }

    /**
     * 计算该OC的期望占用总人天：单岗位期望占用天数乘以团队人数。
     *
     * <p>人数优先取目录配置，配置缺失时退到该队实际岗位数——1至6级OC的required_members整体缺失，
     * 按1人处理会把低级队的人天低估数倍。</p>
     */
    private BigDecimal expectedMemberDays(TornFactionOcDO oc, OcYieldContext context) {
        int configuredMembers = settingOcManager.getList().stream()
                .filter(setting -> Objects.equals(setting.getOcName(), oc.getName()))
                .filter(setting -> Objects.equals(setting.getRank(), oc.getRank()))
                .map(TornSettingOcDO::getRequiredMembers)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(0);
        int members = context.teamSize(oc.getName(), oc.getRank(), configuredMembers);
        return OcRecommendTunings.MEMBER_DAYS_PER_SLOT.multiply(BigDecimal.valueOf(members));
    }
}
