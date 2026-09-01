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
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<Playwright> playwright = new AtomicReference<>();
    private final AtomicReference<Browser> browser = new AtomicReference<>();
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
            if (browser.get() != null && browser.get().isConnected()) {
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

    /**
     * 在一次浏览器重建机会内完成渲染。
     *
     * @param html              待渲染的HTML
     * @param viewportWidth     视口宽度
     * @param deviceScaleFactor 设备像素比
     * @param timeoutSeconds    渲染超时时间，单位为秒
     * @param documentType      文档类型
     * @return PNG二进制内容
     */
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

    /**
     * 使用独立浏览器上下文和页面完成一次HTML截图。
     *
     * @param html              待渲染的HTML
     * @param viewportWidth     视口宽度
     * @param deviceScaleFactor 设备像素比
     * @param deadline           本次渲染的纳秒级截止时间
     * @return PNG二进制内容
     */
    private byte[] renderOnce(String html, int viewportWidth, double deviceScaleFactor, long deadline) {
        Browser currentBrowser = requireBrowser();
        try (BrowserContext context = currentBrowser.newContext(new Browser.NewContextOptions()
                .setViewportSize(viewportWidth, 720)
                .setDeviceScaleFactor(deviceScaleFactor)
                .setJavaScriptEnabled(false)
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK));
             Page page = context.newPage()) {
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
        }
    }

    /**
     * 拒绝页面发起的外部资源请求。
     *
     * @param route 待处理的网络路由
     */
    private void abortExternalRequest(Route route) {
        route.abort();
    }

    /**
     * 获取渲染许可，超时或中断时抛出业务异常。
     */
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

    /**
     * 获取当前可用的浏览器实例。
     *
     * @return 已连接的浏览器实例
     * @throws BizException 浏览器不可用时抛出
     */
    private Browser requireBrowser() {
        Browser currentBrowser = browser.get();
        if (closed || currentBrowser == null || !currentBrowser.isConnected()) {
            throw new BizException("表格图片浏览器不可用");
        }
        return currentBrowser;
    }

    /**
     * 判断当前浏览器是否已断开连接。
     *
     * @return 浏览器不存在或连接已断开时返回true
     */
    private boolean isBrowserDisconnected() {
        Browser currentBrowser = browser.get();
        return currentBrowser == null || !currentBrowser.isConnected();
    }

    /**
     * 计算本次渲染剩余的超时毫秒数。
     *
     * @param deadline 纳秒级截止时间
     * @return 剩余超时毫秒数
     * @throws BizException 渲染已超时时抛出
     */
    private long remainingTimeoutMillis(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new BizException("表格图片渲染超时");
        }
        return Math.max(1, remainingNanos / 1_000_000L);
    }

    /**
     * 创建并启动Chromium浏览器。
     *
     * @throws IllegalStateException Chromium启动失败时抛出
     */
    private void launchBrowser() {
        try {
            playwright.set(Playwright.create());
            browser.set(playwright.get().chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox"))));
        } catch (RuntimeException e) {
            closeResources();
            throw new IllegalStateException("表格图片Chromium启动失败", e);
        }
    }

    /**
     * 关闭当前资源并重新创建浏览器。
     *
     * @param documentType 文档类型，用于异常定位
     * @throws BizException 管理器已关闭或浏览器重建失败时抛出
     */
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

    /**
     * 将渲染异常记录日志并转换为业务异常。
     *
     * @param documentType 文档类型
     * @param cause         原始异常
     * @return 转换后的业务异常
     */
    private BizException renderingFailure(String documentType, Throwable cause) {
        log.error("表格图片渲染失败，documentType={}, exceptionType={}",
                documentType, cause.getClass().getSimpleName(), cause);
        if (cause instanceof BizException bizException) {
            return bizException;
        }
        return new BizException("表格图片渲染失败", cause);
    }

    /**
     * 关闭并清空当前浏览器及Playwright资源。
     */
    private void closeResources() {
        Browser currentBrowser = browser.getAndSet(null);
        try (Playwright ignored = playwright.getAndSet(null)) {
            if (currentBrowser != null) {
                currentBrowser.close();
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
