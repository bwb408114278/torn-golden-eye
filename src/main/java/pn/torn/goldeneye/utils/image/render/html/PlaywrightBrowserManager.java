package pn.torn.goldeneye.utils.image.render.html;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.configuration.property.TableImageRenderProperty;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 管理表格图片渲染所需的单个Playwright Browser生命周期。
 * <p>
 * 每次渲染使用独立Context和Page，并由公平信号量限制为单并发；浏览器崩溃时当前请求最多重建一次。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@Slf4j
@Component
public class PlaywrightBrowserManager implements AutoCloseable {
    private static final String ROOT_SELECTOR = "article.table-image";

    private final TableImageRenderProperty property;
    private final Semaphore renderPermit = new Semaphore(1, true);
    private final Object browserLock = new Object();
    private volatile Playwright playwright;
    private volatile Browser browser;
    private volatile boolean closed;

    /**
     * 创建浏览器生命周期管理器。
     *
     * @param property 表格图片渲染配置
     */
    public PlaywrightBrowserManager(TableImageRenderProperty property) {
        this.property = property;
    }

    /**
     * 在Spring应用启动阶段创建固定的Chromium实例。
     */
    @PostConstruct
    public void start() {
        synchronized (browserLock) {
            if (closed) {
                throw new IllegalStateException("表格图片浏览器管理器已关闭");
            }
            if (browser != null && browser.isConnected()) {
                return;
            }
            closeResources();
            launchBrowser();
        }
    }

    /**
     * 使用隔离的浏览器Context和Page渲染内存HTML并截图。
     *
     * @param html              已完成转义且仅包含固定资源的HTML
     * @param viewportWidth     视口宽度，单位为CSS像素
     * @param deviceScaleFactor 设备像素比
     * @param timeoutSeconds    当前渲染超时时间，单位为秒
     * @param documentType      文档类型，仅用于安全日志和异常定位
     * @return PNG二进制内容
     * @throws BizException 无法取得许可、浏览器失败或截图失败时抛出
     */
    public byte[] render(String html, int viewportWidth, double deviceScaleFactor,
                         int timeoutSeconds, String documentType) {
        acquirePermit();
        try {
            return renderWithSingleRecovery(html, viewportWidth, deviceScaleFactor, timeoutSeconds, documentType);
        } finally {
            renderPermit.release();
        }
    }

    private byte[] renderWithSingleRecovery(String html, int viewportWidth, double deviceScaleFactor,
                                            int timeoutSeconds, String documentType) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            return renderOnce(html, viewportWidth, deviceScaleFactor, deadline);
        } catch (RuntimeException e) {
            if (isBrowserDisconnected()) {
                log.warn("表格图片浏览器异常，尝试重建，documentType={}, exceptionType={}",
                        documentType, e.getClass().getSimpleName());
                rebuildBrowser(documentType);
                try {
                    return renderOnce(html, viewportWidth, deviceScaleFactor, deadline);
                } catch (RuntimeException recoveryException) {
                    throw renderingFailure(documentType, recoveryException);
                }
            }
            throw renderingFailure(documentType, e);
        }
    }

    private byte[] renderOnce(String html, int viewportWidth, double deviceScaleFactor, long deadline) {
        Browser currentBrowser = requireBrowser();
        BrowserContext context = null;
        try {
            context = currentBrowser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(viewportWidth, 720)
                    .setDeviceScaleFactor(deviceScaleFactor)
                    .setJavaScriptEnabled(false)
                    .setServiceWorkers(ServiceWorkerPolicy.BLOCK));
            Page page = context.newPage();
            page.setDefaultTimeout(remainingTimeoutMillis(deadline));
            page.route("**/*", this::abortExternalRequest);
            page.setContent(html, new Page.SetContentOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(remainingTimeoutMillis(deadline)));
            page.locator(ROOT_SELECTOR).waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                    .setTimeout(remainingTimeoutMillis(deadline)));
            return page.locator(ROOT_SELECTOR).screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions()
                    .setType(ScreenshotType.PNG)
                    .setTimeout(remainingTimeoutMillis(deadline)));
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private void abortExternalRequest(Route route) {
        route.abort();
    }

    private void acquirePermit() {
        try {
            if (!renderPermit.tryAcquire(property.getAcquireTimeoutSeconds(), TimeUnit.SECONDS)) {
                throw new BizException("表格图片渲染许可获取超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("表格图片渲染许可获取被中断", e);
        }
    }

    private Browser requireBrowser() {
        Browser currentBrowser = browser;
        if (closed || currentBrowser == null || !currentBrowser.isConnected()) {
            throw new BizException("表格图片浏览器不可用");
        }
        return currentBrowser;
    }

    private boolean isBrowserDisconnected() {
        Browser currentBrowser = browser;
        return currentBrowser == null || !currentBrowser.isConnected();
    }

    private long remainingTimeoutMillis(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new BizException("表格图片渲染超时");
        }
        return Math.max(1, remainingNanos / 1_000_000L);
    }

    private void launchBrowser() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox")));
        } catch (RuntimeException e) {
            closeResources();
            throw new IllegalStateException("表格图片Chromium启动失败", e);
        }
    }

    private void rebuildBrowser(String documentType) {
        synchronized (browserLock) {
            if (closed) {
                throw new BizException("表格图片浏览器管理器已关闭");
            }
            closeResources();
            try {
                launchBrowser();
            } catch (RuntimeException e) {
                throw renderingFailure(documentType, e);
            }
        }
    }

    private BizException renderingFailure(String documentType, Throwable cause) {
        log.error("表格图片渲染失败，documentType={}, exceptionType={}",
                documentType, cause.getClass().getSimpleName(), cause);
        if (cause instanceof BizException bizException) {
            return bizException;
        }
        return new BizException("表格图片渲染失败", cause);
    }

    private void closeResources() {
        Browser currentBrowser = browser;
        browser = null;
        Playwright currentPlaywright = playwright;
        playwright = null;
        try {
            if (currentBrowser != null) {
                currentBrowser.close();
            }
        } finally {
            if (currentPlaywright != null) {
                currentPlaywright.close();
            }
        }
    }

    /**
     * 关闭浏览器及Playwright进程。
     */
    @Override
    @PreDestroy
    public void close() {
        synchronized (browserLock) {
            closed = true;
            closeResources();
        }
    }
}
