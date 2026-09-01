package pn.torn.goldeneye.utils.image.render.html;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.utils.image.document.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 将表格文档映射为固定、安全的HTML片段。
 * <p>
 * 动态文本统一HTML转义，样式只来自受控枚举，不接受调用方传入class、CSS、URL或其他属性。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@Component
public class HtmlTableMarkupRenderer {
    private static final String CSS_RESOURCE = "/table-image/oc-table.css";
    private final String css;

    /**
     * 加载classpath内固定的表格主题CSS。
     */
    public HtmlTableMarkupRenderer() {
        this.css = loadCss();
    }

    /**
     * 将表格文档转换为完整的内存HTML文档。
     *
     * @param document 待转换的表格文档
     * @return 不引用外部资源的完整HTML
     * @throws NullPointerException document为null时抛出
     */
    public String render(TableDocument document) {
        if (document == null) {
            throw new NullPointerException("document不能为null");
        }
        StringBuilder html = new StringBuilder(512);
        html.append("<!doctype html><html lang=\"zh-CN\"><head>")
                .append("<meta charset=\"UTF-8\"><title>")
                .append(escape(document.title()))
                .append("</title><style>")
                .append(css)
                .append("</style></head><body><article class=\"table-image\">")
                .append("<table><tbody>");
        for (TableRow row : document.rows()) {
            html.append("<tr>");
            for (TableCell cell : row.cells()) {
                appendCell(html, cell);
            }
            html.append("</tr>");
        }
        return html.append("</tbody></table></article></body></html>").toString();
    }

    private void appendCell(StringBuilder html, TableCell cell) {
        html.append("<td class=\"")
                .append(styleClass(cell.style()))
                .append(' ')
                .append(overflowClass(cell.overflow()))
                .append('"');
        if (cell.rowSpan() > 1) {
            html.append(" rowspan=\"").append(cell.rowSpan()).append('"');
        }
        if (cell.colSpan() > 1) {
            html.append(" colspan=\"").append(cell.colSpan()).append('"');
        }
        html.append('>').append(escape(cell.text())).append("</td>");
    }

    private String styleClass(TableCellStyleEnum style) {
        return switch (style) {
            case TITLE -> "cell-title";
            case SECTION -> "cell-section";
            case TEAM_READY -> "cell-team-ready";
            case TEAM_WARNING -> "cell-team-warning";
            case SLOT_FILLED -> "cell-slot-filled";
            case SLOT_EMPTY -> "cell-slot-empty";
            case SLOT_RECOMMENDED -> "cell-slot-recommended";
            case SLOT_IDLE -> "cell-slot-idle";
            case MEMBER_FILLED -> "cell-member-filled";
            case MEMBER_EMPTY -> "cell-member-empty";
            case FOOTER -> "cell-footer";
        };
    }

    private String overflowClass(TableTextOverflowEnum overflow) {
        return switch (overflow) {
            case WRAP -> "overflow-wrap";
            case ELLIPSIS -> "overflow-ellipsis";
            case CLIP -> "overflow-clip";
        };
    }

    private String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String loadCss() {
        try (InputStream inputStream = HtmlTableMarkupRenderer.class.getResourceAsStream(CSS_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("表格图片CSS资源不存在");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("表格图片CSS资源读取失败", e);
        }
    }
}
