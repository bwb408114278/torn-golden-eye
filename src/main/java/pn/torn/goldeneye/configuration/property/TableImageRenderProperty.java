package pn.torn.goldeneye.configuration.property;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 表格图片渲染配置。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@Data
@Component
@ConfigurationProperties(prefix = "table-image-render")
public class TableImageRenderProperty {
    /**
     * 单次浏览器渲染允许使用的最长时间，单位为秒。
     */
    private int renderTimeoutSeconds = 10;

    /**
     * 等待渲染并发许可的最长时间，单位为秒。
     */
    private int acquireTimeoutSeconds = 3;

    /**
     * 浏览器视口宽度，单位为 CSS 像素。
     */
    private int viewportWidth = 1600;

    /**
     * 浏览器设备像素比。
     */
    private double deviceScaleFactor = 1;

    /**
     * 校验配置，避免实际机器人请求才发现渲染参数非法。
     */
    @PostConstruct
    public void validate() {
        if (renderTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("table-image-render.render-timeout-seconds必须大于0");
        }
        if (acquireTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("table-image-render.acquire-timeout-seconds必须大于0");
        }
        if (viewportWidth <= 0) {
            throw new IllegalArgumentException("table-image-render.viewport-width必须大于0");
        }
        if (!Double.isFinite(deviceScaleFactor) || deviceScaleFactor <= 0) {
            throw new IllegalArgumentException("table-image-render.device-scale-factor必须为正数");
        }
    }
}
