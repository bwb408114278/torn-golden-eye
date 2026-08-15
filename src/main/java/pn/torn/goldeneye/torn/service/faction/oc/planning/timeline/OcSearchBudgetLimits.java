package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

/**
 * 时间线多状态搜索的只读技术预算定义。
 *
 * <p>这些上限只描述搜索资源边界，不是业务容量参数：多状态搜索接近状态上限时
 * 分支耗时放大，更容易触发预算截断。任一预算截断都必须映射为
 * {@code UNPROVEN_SEARCH_BUDGET}并保留安全下界语义，
 * 不得解释为已证明不可行或卡死风险；是否命中记录在匿名Shadow日志中。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public final class OcSearchBudgetLimits {
    /**
     * 单次模拟允许的义务展开总次数上限，超出即视为搜索预算截断。
     */
    public static final int MAX_TASK_EXPANSIONS = 256;

    /**
     * 每个义务在基础匹配之外保留的替代匹配方案上限（含基础方案在内的候选总数上限）。
     */
    public static final int MAX_MATCH_ALTERNATIVES = 4;

    /**
     * 多状态搜索同时保留的活跃状态上限，超出即视为搜索预算截断。
     */
    public static final int MAX_ACTIVE_STATES = 16;

    private OcSearchBudgetLimits() {
    }
}
