package pn.torn.goldeneye.utils.image.document;

/**
 * 表格单元格的有限语义样式。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
public enum TableCellStyleEnum {
    /**
     * 标题。
     */
    TITLE,
    /**
     * OC分隔行。
     */
    SECTION,
    /**
     * 团队已准备。
     */
    TEAM_READY,
    /**
     * 团队存在警告。
     */
    TEAM_WARNING,
    /**
     * 已填充岗位。
     */
    SLOT_FILLED,
    /**
     * 空岗位。
     */
    SLOT_EMPTY,
    /**
     * 推荐岗位。
     */
    SLOT_RECOMMENDED,
    /**
     * 空转岗位。
     */
    SLOT_IDLE,
    /**
     * 当前OC查询中真实空缺岗位的上行。
     */
    CURRENT_SLOT_EMPTY,
    /**
     * 当前OC查询中真实空缺岗位的下行空缺成员位。
     */
    CURRENT_MEMBER_EMPTY,
    /**
     * 已填充成员。
     */
    MEMBER_FILLED,
    /**
     * 空成员位。
     */
    MEMBER_EMPTY,
    /**
     * 页脚。
     */
    FOOTER
}
