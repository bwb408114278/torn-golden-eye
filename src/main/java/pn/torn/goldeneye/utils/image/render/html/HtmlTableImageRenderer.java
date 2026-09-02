package pn.torn.goldeneye.utils.image.render.html;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.configuration.property.TableImageRenderProperty;
import pn.torn.goldeneye.utils.image.document.TableDocument;
import pn.torn.goldeneye.utils.image.render.TableImageRenderer;

import java.util.Base64;

/**
 * 使用固定HTML主题和Playwright Chromium生成表格PNG图片。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HtmlTableImageRenderer implements TableImageRenderer {
    private final HtmlTableMarkupRenderer markupRenderer;
    private final PlaywrightBrowserManager browserManager;
    private final TableImageRenderProperty property;

    /**
     * 将表格文档渲染为不带协议前缀的PNG Base64。
     *
     * @param document 待渲染的表格文档
     * @return PNG Base64
     * @throws BizException 表格标记或浏览器渲染失败时抛出
     */
    @Override
    public String render(TableDocument document) {
        if (document == null) {
            throw new BizException("表格图片渲染失败", new NullPointerException("document不能为null"));
        }
        try {
            String html = markupRenderer.render(document);
            byte[] png = browserManager.render(html, property.getViewportWidth(), property.getDeviceScaleFactor(),
                    property.getRenderTimeoutSeconds(), document.documentType());
            return Base64.getEncoder().encodeToString(png);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("表格图片渲染失败，documentType={}, exceptionType={}",
                    document.documentType(), e.getClass().getSimpleName(), e);
            throw new BizException("表格图片渲染失败，文档类型=" + document.documentType(), e);
        }
    }
}
