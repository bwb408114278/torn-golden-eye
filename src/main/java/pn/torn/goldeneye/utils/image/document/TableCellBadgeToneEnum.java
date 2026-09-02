package pn.torn.goldeneye.utils.image.document;

/**
 * 表格单元格徽章的通用视觉色调。
 * <p>
 * 该枚举只表达受控的展示色调语义，不承载OC业务状态、DAO、浏览器或CSS文本依赖；
 * 业务时间状态到色调的映射由文档组装层完成。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.09.01
 */
public enum TableCellBadgeToneEnum {
    /**
     * 成功色调。
     */
    SUCCESS,
    /**
     * 信息色调。
     */
    INFO,
    /**
     * 警告色调。
     */
    WARNING,
    /**
     * 危险色调。
     */
    DANGER
}
