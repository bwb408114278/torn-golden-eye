package pn.torn.goldeneye.base.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import pn.torn.goldeneye.constants.InitOrderConstants;
import pn.torn.goldeneye.constants.torn.CacheConstants;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 缓存配置类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.11.20
 */
@Configuration
@EnableCaching
@RequiredArgsConstructor
@Slf4j
public class CacheConfig {
    private final ApplicationContext applicationContext;

    @EventListener(ApplicationReadyEvent.class)
    @Order(InitOrderConstants.CACHE)
    public void init() {
        Map<String, DataCacheManager> cacheManagers = applicationContext.getBeansOfType(DataCacheManager.class);
        for (Map.Entry<String, DataCacheManager> entry : cacheManagers.entrySet()) {
            DataCacheManager manager = entry.getValue();
            manager.warmUpCache();
        }
    }

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        List<String> cacheNames = collectCacheNames();
        cacheManager.setCacheNames(cacheNames);

        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.HOURS));
        return cacheManager;
    }

    /**
     * 通过反射收集 CacheConstants 中的所有缓存名称
     */
    private List<String> collectCacheNames() {
        List<String> cacheNames = new ArrayList<>();

        Field[] fields = CacheConstants.class.getDeclaredFields();
        for (Field field : fields) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())
                    && field.getType() == String.class
                    && field.getName().startsWith("KEY_")) {

                try {
                    String cacheName = (String) field.get(null);
                    cacheNames.add(cacheName);
                } catch (IllegalAccessException ignored) {
                }
            }
        }

        return cacheNames;
    }
}