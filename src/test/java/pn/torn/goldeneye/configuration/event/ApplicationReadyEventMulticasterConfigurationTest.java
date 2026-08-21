package pn.torn.goldeneye.configuration.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 应用就绪事件多播器配置测试，验证启动补偿异常隔离和其他事件的默认传播语义。
 *
 * @author Bai
 * @version 1.3.11
 * @since 2026.08.21
 */
@DisplayName("应用就绪事件多播器配置测试")
class ApplicationReadyEventMulticasterConfigurationTest {

    @Test
    @DisplayName("应用就绪监听器异常时多播不抛出异常")
    void readyEventListenerExceptionDoesNotEscape() {
        ApplicationEventMulticaster multicaster = createMulticaster();
        ApplicationReadyEvent event = createReadyEvent();
        multicaster.addApplicationListener((ApplicationListener<ApplicationReadyEvent>) readyEvent -> {
            throw new IllegalStateException("api model changed");
        });

        assertDoesNotThrow(() -> multicaster.multicastEvent(event));
    }

    @Test
    @DisplayName("应用就绪前一个监听器失败后续监听器仍继续执行")
    void failedReadyListenerDoesNotBlockFollowingListener() {
        ApplicationEventMulticaster multicaster = createMulticaster();
        ApplicationReadyEvent event = createReadyEvent();
        AtomicBoolean invoked = new AtomicBoolean();
        multicaster.addApplicationListener((ApplicationListener<ApplicationReadyEvent>) readyEvent -> {
            throw new IllegalStateException("api model changed");
        });
        multicaster.addApplicationListener((ApplicationListener<ApplicationReadyEvent>) readyEvent -> invoked.set(true));

        assertDoesNotThrow(() -> multicaster.multicastEvent(event));
        org.junit.jupiter.api.Assertions.assertTrue(invoked.get());
    }

    @Test
    @DisplayName("非应用就绪事件监听器异常仍向外抛出")
    void nonReadyEventListenerExceptionEscapes() {
        ApplicationEventMulticaster multicaster = createMulticaster();
        CustomApplicationEvent event = new CustomApplicationEvent(this);
        multicaster.addApplicationListener((ApplicationListener<CustomApplicationEvent>) customEvent -> {
            throw new IllegalStateException("custom event failed");
        });

        assertThrows(IllegalStateException.class, () -> multicaster.multicastEvent(event));
    }

    @Test
    @DisplayName("应用就绪正常监听器同步执行一次")
    void readyEventListenerRunsSynchronouslyOnce() {
        ApplicationEventMulticaster multicaster = createMulticaster();
        ApplicationReadyEvent event = createReadyEvent();
        AtomicInteger invocationCount = new AtomicInteger();
        multicaster.addApplicationListener((ApplicationListener<ApplicationReadyEvent>) readyEvent ->
                invocationCount.incrementAndGet());

        multicaster.multicastEvent(event);

        assertEquals(1, invocationCount.get());
    }

    private ApplicationEventMulticaster createMulticaster() {
        return new ApplicationReadyEventMulticasterConfiguration().applicationEventMulticaster();
    }

    private ApplicationReadyEvent createReadyEvent() {
        SpringApplication application = mock(SpringApplication.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        return new ApplicationReadyEvent(application, new String[0], context, Duration.ZERO);
    }

    private static final class CustomApplicationEvent extends ApplicationEvent {

        private CustomApplicationEvent(Object source) {
            super(source);
        }
    }
}
