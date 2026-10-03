package pn.torn.goldeneye.torn.model.activity;

import java.util.Optional;

/**
 * 活跃度热力图统计口径。
 * <p>
 * 只承载指令关键字、窗口天数与格的时间跨度文案；行列形状与标签由
 * {@code ActivityGridLayout} 回答，两者分离后新增口径时两种改动各自只落在一处。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.10.03
 */
public enum ActivityCaliberEnum {

    /**
     * 单日：窗口仅锚点日一天，一格覆盖 15 分钟。
     */
    SINGLE_DAY("单日", 1, "15 分钟"),
    /**
     * 单周：窗口为以锚点日为最后一天的近 7 天，一格覆盖 1 小时。
     */
    SINGLE_WEEK("单周", 7, "1 小时"),
    /**
     * 半月：窗口为以锚点日为最后一天的近 15 天，一格覆盖 1 小时。
     */
    HALF_MONTH("半月", 15, "1 小时"),
    /**
     * 典型周：窗口为以锚点日为最后一天的近 28 天，同一(星期, 小时)的槽累计进同一格，故格值为窗口均值。
     */
    TYPICAL_WEEK("典型周", 28, "1 小时，取窗口均值");

    private final String keyword;
    private final int windowDays;
    private final String cellSpanLabel;

    ActivityCaliberEnum(String keyword, int windowDays, String cellSpanLabel) {
        this.keyword = keyword;
        this.windowDays = windowDays;
        this.cellSpanLabel = cellSpanLabel;
    }

    /**
     * 指令关键字。
     *
     * @return 关键字
     */
    public String keyword() {
        return keyword;
    }

    /**
     * 窗口天数（自然日，含锚点日）。
     *
     * @return 窗口天数
     */
    public int windowDays() {
        return windowDays;
    }

    /**
     * 一格覆盖的时间跨度展示文案。
     *
     * @return 展示文案
     */
    public String cellSpanLabel() {
        return cellSpanLabel;
    }

    /**
     * 按指令关键字精确匹配口径。
     *
     * @param text 指令段原文
     * @return 命中口径；未命中返回空，由调用方回复格式说明
     */
    public static Optional<ActivityCaliberEnum> fromKeyword(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        for (ActivityCaliberEnum caliber : values()) {
            if (caliber.keyword.equals(trimmed)) {
                return Optional.of(caliber);
            }
        }
        return Optional.empty();
    }
}
