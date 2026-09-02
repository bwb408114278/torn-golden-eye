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

    /**
     * 将单元格追加到HTML表格中。
     *
     * @param html HTML文档构建器
     * @param cell 待追加的表格单元格
     */
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
        html.append('>');
        appendContent(html, cell.content());
        html.append("</td>");
    }

    /**
     * 通过对内容模型的穷尽分派输出固定标签结构。
     *
     * @param html    HTML文档构建器
     * @param content 单元格受控内容
     */
    private void appendContent(StringBuilder html, TableCellContent content) {
        switch (content) {
            case TableCellContent.PlainText plainText -> appendPlainTextContent(html, plainText);
            case TableCellContent.BadgeText badgeText -> appendBadgeTextContent(html, badgeText);
            case TableCellContent.ThreePartText threePartText -> appendThreePartTextContent(html, threePartText);
        }
    }

    /**
     * 输出纯文本内容。
     *
     * @param html      HTML文档构建器
     * @param plainText 纯文本内容
     */
    private void appendPlainTextContent(StringBuilder html, TableCellContent.PlainText plainText) {
        appendEscapedText(html, plainText.text());
    }

    /**
     * 输出固定居中容器内的名称与一个或多个状态徽章。
     *
     * @param html      HTML文档构建器
     * @param badgeText 徽章内容
     */
    private void appendBadgeTextContent(StringBuilder html, TableCellContent.BadgeText badgeText) {
        html.append("<span class=\"cell-section-head\"><span class=\"cell-section-name\">");
        appendEscapedText(html, badgeText.primaryText());
        html.append("</span>");
        for (TableCellContent.Badge badge : badgeText.badges()) {
            html.append("<span class=\"cell-badge ")
                    .append(badgeToneClass(badge.badgeTone()))
                    .append("\">");
            appendEscapedText(html, badge.text());
            html.append("</span>");
        }
        html.append("</span>");
    }

    /**
     * 输出固定岗位容器内的左、中、右三段。
     *
     * @param html          HTML文档构建器
     * @param threePartText 三段式内容
     */
    private void appendThreePartTextContent(StringBuilder html, TableCellContent.ThreePartText threePartText) {
        html.append("<span class=\"slot-parts\"><span class=\"slot-part-leading\">");
        appendEscapedText(html, threePartText.leadingText());
        html.append("</span><span class=\"slot-part-center\">");
        appendEscapedText(html, threePartText.centerText());
        html.append("</span><span class=\"slot-part-trailing\">");
        appendEscapedText(html, threePartText.trailingText());
        html.append("</span></span>");
    }

    /**
     * 追加转义后的动态文本片段。
     *
     * @param html HTML文档构建器
     * @param text 待转义文本
     */
    private void appendEscapedText(StringBuilder html, String text) {
        html.append(escape(text));
    }

    /**
     * 将徽章色调映射为固定CSS类名。
     *
     * @param tone 受控徽章色调
     * @return CSS类名
     */
    private String badgeToneClass(TableCellBadgeToneEnum tone) {
        return switch (tone) {
            case SUCCESS -> "badge-success";
            case INFO -> "badge-info";
            case WARNING -> "badge-warning";
            case DANGER -> "badge-danger";
            case NEUTRAL -> "badge-neutral";
        };
    }

    /**
     * 将单元格样式映射为固定CSS类名。
     *
     * @param style 受控单元格样式
     * @return CSS类名
     */
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
            case CURRENT_SLOT_EMPTY -> "cell-current-slot-empty";
            case CURRENT_MEMBER_EMPTY -> "cell-current-member-empty";
            case MEMBER_FILLED -> "cell-member-filled";
            case MEMBER_EMPTY -> "cell-member-empty";
            case FOOTER -> "cell-footer";
        };
    }

    /**
     * 将文本溢出策略映射为固定CSS类名。
     *
     * @param overflow 受控文本溢出策略
     * @return CSS类名
     */
    private String overflowClass(TableTextOverflowEnum overflow) {
        return switch (overflow) {
            case WRAP -> "overflow-wrap";
            case ELLIPSIS -> "overflow-ellipsis";
            case CLIP -> "overflow-clip";
        };
    }

    /**
     * 转义HTML文本中的特殊字符。
     *
     * @param text 待转义文本
     * @return HTML安全文本
     */
    private String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * 从classpath加载固定表格主题CSS。
     *
     * @return CSS文本
     * @throws IllegalStateException CSS资源不存在或读取失败时抛出
     */
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
