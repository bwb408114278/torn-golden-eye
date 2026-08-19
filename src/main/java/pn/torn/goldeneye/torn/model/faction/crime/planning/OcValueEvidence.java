package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一个候选向量或完整链的价值证据。高阶根按根和全部后继的完整链聚合后参与比较。
 * 业务先验字段{@code highestRank}、{@code totalRequiredMembers}、{@code chainNodeCount}
 * 用于金额证据不足时的第三层比较；普通OC节点数为1。
 *
 * @param level                   价值证据层级
 * @param totalValue              完整OC或完整链价值；金额证据不足时为null
 * @param incrementalMemberDays   增量剩余成员人天
 * @param expectedReleaseAt       预计最早完整释放时间
 * @param usableForAdviceIncrease 证据信息完整性；最终是否可用由候选比较结果决定
 * @param highestRank             完整候选（含高阶链全部后继）的最高等级
 * @param totalRequiredMembers    完整候选（含高阶链全部后继）的总需人数
 * @param chainNodeCount          完整候选的链节点数；普通OC为1
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcValueEvidence(
        Level level,
        BigDecimal totalValue,
        int incrementalMemberDays,
        LocalDateTime expectedReleaseAt,
        boolean usableForAdviceIncrease,
        int highestRank,
        int totalRequiredMembers,
        int chainNodeCount) {

    /**
     * 兼容旧构造路径：普通OC先验默认节点数为1、等级和人数回退为0。
     * 新生产构造路径必须显式提供业务先验字段。
     */
    public OcValueEvidence(Level level, BigDecimal totalValue, int incrementalMemberDays,
                           LocalDateTime expectedReleaseAt, boolean usableForAdviceIncrease) {
        this(level, totalValue, incrementalMemberDays, expectedReleaseAt,
                usableForAdviceIncrease, 0, incrementalMemberDays, 1);
    }

    /**
     * 价值证据降级层级，按业务冻结顺序从强到弱。
     */
    public enum Level {
        /**
         * 样本达到业务最小样本数且奖励数据完整，使用观察每次尝试收益。
         */
        OBSERVED_REWARD,
        /**
         * 样本不足但存在正的可靠收益下界。
         */
        REWARD_FLOOR,
        /**
         * 金额证据不足，使用等级、人数、链节点和人天业务先验。
         */
        PRIOR_ONLY,
        /**
         * 仍无法稳定区分，经济证据不足。
         */
        INSUFFICIENT
    }
}
