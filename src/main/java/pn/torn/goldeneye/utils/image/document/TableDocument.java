package pn.torn.goldeneye.utils.image.document;

import java.util.List;
import java.util.Objects;

/**
 * 与具体渲染技术无关的不可变表格图片文档。
 *
 * @param title        文档标题，不能为空白
 * @param rows         表格行，不能为空且不能包含null
 * @param width        期望输出宽度，必须大于0
 * @param documentType 文档类型标识，不能为空白
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
public record TableDocument(
        String title,
        List<TableRow> rows,
        int width,
        String documentType) {

    /**
     * 创建并校验表格文档，同时防御性复制行集合。
     */
    public TableDocument {
        Objects.requireNonNull(title, "title不能为null");
        Objects.requireNonNull(rows, "rows不能为null");
        Objects.requireNonNull(documentType, "documentType不能为null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title不能为空白");
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("表格行不能为空");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("width必须大于0");
        }
        if (documentType.isBlank()) {
            throw new IllegalArgumentException("documentType不能为空白");
        }
        rows = List.copyOf(rows);
    }
}
