package pn.torn.goldeneye.utils.image.document;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 表格文档主题注册表。
 *
 * <p>每个主题登记自己的文档类型标识与主题层CSS资源，通用基础层由注册表统一前置，
 * 文档类型到样式的解析只在此处发生。未注册的文档类型快速失败，不静默回退到其他主题。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.15
 */
@AllArgsConstructor
public enum TableThemeEnum {
    /**
     * OC主题：通用基础层 + OC专有层
     */
    OC("oc-table", "/table-image/oc-table.css"),
    /**
     * PC赛车主题：通用基础层 + PC赛车专有层
     */
    PC_RACE("pc-race-table", "/table-image/pc-race-table.css"),
    /**
     * RW贡献榜主题：通用基础层 + RW贡献榜专有层
     */
    RW_CONTRIBUTION("rw-contribution-table", "/table-image/rw-contribution-table.css");

    /**
     * 全部主题共用的通用基础层CSS资源，按层叠顺序位于主题层之前
     */
    private static final String BASE_CSS = "/table-image/table-base.css";

    /**
     * 获取主题的文档类型标识。
     */
    @Getter
    private final String documentType;
    /**
     * 主题专有层CSS资源路径
     */
    private final String themeCss;

    /**
     * 获取按层叠顺序排列的CSS资源，通用基础层在前、主题专有层在后。
     *
     * @return CSS资源路径列表
     */
    public List<String> getCssResources() {
        return List.of(BASE_CSS, themeCss);
    }

    /**
     * 按文档类型解析主题。
     *
     * @param documentType 文档类型标识
     * @return 已注册的主题
     * @throws IllegalArgumentException 文档类型为null或未注册时抛出
     */
    public static TableThemeEnum of(String documentType) {
        for (TableThemeEnum theme : values()) {
            if (theme.documentType.equals(documentType)) {
                return theme;
            }
        }
        throw new IllegalArgumentException("未注册的表格主题: " + documentType);
    }
}
