package pn.torn.goldeneye.utils.image.render;

import pn.torn.goldeneye.utils.image.document.TableDocument;

/**
 * 表格图片渲染唯一公共抽象。
 * <p>
 * 实现必须返回不带 {@code base64://} 前缀的PNG Base64，并将渲染失败转换为统一业务异常。
 * 文档模型不得依赖具体浏览器或图片实现。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
public interface TableImageRenderer {
    /**
     * 将不可变表格文档渲染为PNG Base64。
     *
     * @param document 待渲染的表格文档
     * @return 不带协议前缀的PNG Base64
     * @throws pn.torn.goldeneye.base.exception.BizException 文档渲染失败时抛出
     */
    String render(TableDocument document);
}
