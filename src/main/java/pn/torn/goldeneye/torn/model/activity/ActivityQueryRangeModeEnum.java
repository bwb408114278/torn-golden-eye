package pn.torn.goldeneye.torn.model.activity;

/**
 * 活跃度热力图查询范围模式
 * <p>
 * 表达用户指令中日期参数的解析形态：无日期参数等价最近28天（DEFAULT），
 * {@code 从#日期}以起始日期锚定（FROM），{@code 截至#日期}以结束日期向前28天锚定（UNTIL）。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
public enum ActivityQueryRangeModeEnum {

    /**
     * 无日期参数：[今天 - 27 天, 今天]，兼容既有最近 28 天语义
     */
    DEFAULT,

    /**
     * 从#yyyy-MM-dd：[startDate, 今天]
     */
    FROM,

    /**
     * 截至#yyyy-MM-dd：[endDate - 27 天, endDate]
     */
    UNTIL
}
