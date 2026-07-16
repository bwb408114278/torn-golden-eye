package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * OC岗位候选成员。能力Key格式为 rank:ocName:position。
 *
 * @param userId 成员用户ID
 * @param nickname 成员昵称
 * @param availableAt 成员最早可参与新阶段的时间
 * @param fixed 是否为旧队固定成员
 * @param passRates 按OC与岗位索引的成功率映射
 * @param coefficients 按OC与岗位索引的工时评价系数映射
 */public record OcMemberCandidate(long userId, String nickname, LocalDateTime availableAt,
                                boolean fixed, Map<String, Integer> passRates,
                                Map<String, BigDecimal> coefficients) {

    public OcMemberCandidate {
        passRates = passRates == null ? Map.of() : Map.copyOf(passRates);
        coefficients = coefficients == null ? Map.of() : Map.copyOf(coefficients);
    }

    /**
     * 查询成员在指定OC岗位的成功率。
     *
     * @param rank OC等级
     * @param ocName OC名称
     * @param position 岗位名称
     * @return 成功率；无能力数据时返回-1
     */
    public int getPassRate(int rank, String ocName, String position) {
        return passRates.getOrDefault(capabilityKey(rank, ocName, position), -1);
    }

    /**
     * 查询成员在指定OC岗位的工时评价系数。
     *
     * @param rank OC等级
     * @param ocName OC名称
     * @param slotCode 岗位编码
     * @return 工时评价系数；无系数数据时返回0
     */
    public BigDecimal getCoefficient(int rank, String ocName, String slotCode) {
        return coefficients.getOrDefault(capabilityKey(rank, ocName, slotCode), BigDecimal.ZERO);
    }

    /**
     * 统计成员已记录成功率的OC岗位数量。
     *
     * @return 已记录成功率的岗位数量
     */
    public int getCapabilityCount() {
        return passRates.size();
    }

    /**
     * 创建在指定时间恢复可用且解除固定状态的成员副本。
     *
     * @param time 新的最早可用时间
     * @return 更新可用时间并解除固定状态的成员副本
     */
    public OcMemberCandidate asAvailableAt(LocalDateTime time) {
        return new OcMemberCandidate(userId, nickname, time, false, passRates, coefficients);
    }

    /**
     * 创建保持当前可用时间并标记为固定成员的副本。
     *
     * @return 标记为固定状态的成员副本
     */
    public OcMemberCandidate asFixed() {
        return new OcMemberCandidate(userId, nickname, availableAt, true, passRates, coefficients);
    }

    /**
     * 创建同时更新可用时间和固定状态的成员副本。
     *
     * @param time 新的最早可用时间
     * @param fixedState 新的固定状态
     * @return 更新后的成员副本
     */
    public OcMemberCandidate withAvailability(LocalDateTime time, boolean fixedState) {
        return new OcMemberCandidate(userId, nickname, time, fixedState, passRates, coefficients);
    }

    /**
     * 构造OC岗位能力数据的统一索引键。
     *
     * @param rank OC等级
     * @param ocName OC名称
     * @param position 岗位名称或岗位编码
     * @return 格式为等级、OC名称和岗位三段组合的索引键
     */
    public static String capabilityKey(int rank, String ocName, String position) {
        return rank + ":" + ocName + ":" + position;
    }
}
