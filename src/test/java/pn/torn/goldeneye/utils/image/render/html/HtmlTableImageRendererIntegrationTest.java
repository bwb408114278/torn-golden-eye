package pn.torn.goldeneye.utils.image.render.html;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import pn.torn.goldeneye.configuration.property.TableImageRenderProperty;
import pn.torn.goldeneye.utils.image.document.*;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chromium真实渲染集成测试。
 * <p>
 * 仅在设置{@code TABLE_IMAGE_RENDER_INTEGRATION=true}时执行；未设置时由JUnit明确跳过，不能视为通过。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@DisplayName("HTML表格图片Chromium集成测试")
@EnabledIfEnvironmentVariable(named = "TABLE_IMAGE_RENDER_INTEGRATION", matches = "true")
class HtmlTableImageRendererIntegrationTest {
    @Test
    @DisplayName("应通过Chromium生成PNG并保留中文和Emoji输入")
    void shouldRenderPngWithChromium() {
        TableImageRenderProperty property = new TableImageRenderProperty();
        property.validate();
        PlaywrightBrowserManager browserManager = new PlaywrightBrowserManager(property);
        browserManager.start();
        try {
            HtmlTableImageRenderer renderer = new HtmlTableImageRenderer(
                    new HtmlTableMarkupRenderer(), browserManager, property);
            String result = renderer.render(new TableDocument("中文 💤 ⏳ ✅ ⚠️", List.of(new TableRow(List.of(
                    new TableCell("阶段A", TableCellStyleEnum.TITLE, 1, 1, TableTextOverflowEnum.WRAP)))),
                    1600, "integration"));

            byte[] png = Base64.getDecoder().decode(result);
            assertTrue(png.length > 8);
            assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}, new byte[]{png[0], png[1], png[2], png[3]});
        } finally {
            browserManager.close();
        }
    }
}
