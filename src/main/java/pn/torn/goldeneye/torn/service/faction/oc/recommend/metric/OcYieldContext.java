package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * OC产出估算上下文：一次批量估算内共享的只读事实。
 *
 * <p>把「时间基准」与「每支队的实际岗位数」一起传下去，是为了让估算器保持无状态：
 * 时间由调用方传入（估算器不得读系统时间），岗位数由调用方批量查一次（估算器不得逐个查库）。
 * 岗位数按「OC名称+等级」索引，因此链式OC的前置步骤也能取到自己的岗位数。</p>
 *
 * @param snapshotTime 本次推荐的时间基准
 * @param teamSizeByOc 每支队的岗位数，Key为「OC名称+等级」，只在配置缺失时才使用
 * @author Bai
 * @version 1.9.4
 * @since 2026.10.25
 */
public record OcYieldContext(LocalDateTime snapshotTime, Map<String, Integer> teamSizeByOc) {

    /**
     * 归一化空值，避免调用方做空判断。
     */
    public OcYieldContext {
        teamSizeByOc = teamSizeByOc == null ? Map.of() : Map.copyOf(teamSizeByOc);
    }

    /**
     * 解析团队人数，优先级为「目录配置 > 实际岗位数 > 1人」。
     *
     * <p>目录配置的required_members在1至6级OC上整体缺失（配置只做到7级往上），缺失时若按1人处理，
     * 会把4至6人队的人天低估3至6倍，进而把单位人天产出高估同样倍数——低级队因此会挤掉高级队。
     * 实际岗位数从torn_faction_oc_slot按oc_id统计，是同一支队的真实编制，作为第二优先级。</p>
     *
     * @param ocName            OC名称
     * @param rank              OC等级
     * @param configuredMembers 目录配置的岗位人数，可为空
     * @return 团队人数，至少为1
     */
    public int teamSize(String ocName, Integer rank, Integer configuredMembers) {
        if (configuredMembers != null && configuredMembers > 0) {
            return configuredMembers;
        }
        int fromSlots = teamSizeByOc.getOrDefault(keyOf(ocName, rank), 0);
        return Math.max(1, fromSlots);
    }

    /**
     * 构造岗位数索引键。
     *
     * @param ocName OC名称
     * @param rank   OC等级
     * @return 索引键
     */
    public static String keyOf(String ocName, Integer rank) {
        return ocName + "|" + rank;
    }
}
