package pn.torn.goldeneye.base.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;

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
 * @version 0.3.0
 * @since 2025.11.20
 */
@Configuration
@EnableCaching
@RequiredArgsConstructor
@Slf4j
public class CacheConfig {
    private final ApplicationContext applicationContext;
    private final TornItemsManager itemsManager;

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void init() {
        log.info("🔥 开始预热所有缓存...");
        long startTime = System.currentTimeMillis();

        Map<String, DataCacheManager> cacheManagers = applicationContext.getBeansOfType(DataCacheManager.class);

        int successCount = 0;
        int failCount = 0;

        for (Map.Entry<String, DataCacheManager> entry : cacheManagers.entrySet()) {
            String beanName = entry.getKey();
            DataCacheManager manager = entry.getValue();
            try {
                log.info("  ⏳ 正在预热: {}", beanName);
                manager.warmUpCache();
                log.info("  ✅ 预热成功: {}", beanName);
                successCount++;
            } catch (Exception e) {
                log.error("  ❌ 预热失败: {}, 错误: {}", beanName, e.getMessage(), e);
                failCount++;
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("🎉 缓存预热完成! 成功: {}, 失败: {}, 耗时: {}ms", successCount, failCount, duration);
    }

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        List<String> cacheNames = collectCacheNames();
        cacheManager.setCacheNames(cacheNames);

        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(24, TimeUnit.HOURS));
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