package pn.torn.goldeneye.configuration.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;

/**
 * 应用事件多播器配置。
 *
 * <p>这是部署启动补偿的 P0 防线，仅隔离 {@link ApplicationReadyEvent} 监听器执行时抛出的
 * {@link Exception}，避免外部 API 契约变化阻断服务启动。其他 Spring 事件保持默认异常传播语义不变。</p>
 *
 * @author Bai
 * @version 1.3.11
 * @since 2026.08.21
 */
@Configuration
@Slf4j
public class ApplicationReadyEventMulticasterConfiguration {

    /**
     * 注册保持同步派发的应用事件多播器，并仅对应用就绪事件隔离监听器异常。
     *
     * @return 应用事件多播器
     */
    @Bean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME)
    public ApplicationEventMulticaster applicationEventMulticaster() {
        return new StartupSafeApplicationEventMulticaster();
    }

    /**
     * 仅在应用就绪事件边界隔离启动补偿监听器异常的多播器。
     */
    private static final class StartupSafeApplicationEventMulticaster
            extends SimpleApplicationEventMulticaster {

        @Override
        protected void invokeListener(ApplicationListener<?> listener, ApplicationEvent event) {
            if (!(event instanceof ApplicationReadyEvent)) {
                super.invokeListener(listener, event);
                return;
            }
            try {
                super.invokeListener(listener, event);
            } catch (Exception exception) {
                log.error("应用就绪补偿监听器执行失败，不影响服务启动, eventType={}, listener={}",
                        event.getClass().getName(), listener.getClass().getName(), exception);
            }
        }
    }
}
