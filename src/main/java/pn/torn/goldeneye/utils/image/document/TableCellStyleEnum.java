package pn.torn.goldeneye.utils.image.document;

/**
 * 表格单元格的有限语义样式。
 *
 * @author Bai
 * @version 1.9.0
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
    FOOTER,
    /**
     * 表头行。
     */
    HEADER,
    /**
     * 普通数据行。
     */
    BODY,
    /**
     * 第一名。
     */
    RANK_FIRST,
    /**
     * 第二名。
     */
    RANK_SECOND,
    /**
     * 第三名。
     */
    RANK_THIRD,
    /**
     * 成功率超出帮派要求10及以上的数值格。
     */
    RATE_EXCEED,
    /**
     * 成功率达到帮派要求但超出不足10的数值格。
     */
    RATE_PASS,
    /**
     * 成功率低于帮派要求且差距小于10的数值格。
     */
    RATE_FAIL_NEAR,
    /**
     * 成功率低于帮派要求且差距达到10及以上的数值格。
     */
    RATE_FAIL_FAR,
    /**
     * 该岗位无成功率记录的数值格。
     */
    RATE_NONE,
    /**
     * 入门OC（7级及以下）的级别与名称合并格。
     */
    OC_GROUP_ENTRY,
    /**
     * 核心OC（非前置的8级）的级别与名称合并格。
     */
    OC_GROUP_CORE,
    /**
     * 连锁OC（8级连锁前置与9、10级）的级别与名称合并格。
     */
    OC_GROUP_CHAIN
}
