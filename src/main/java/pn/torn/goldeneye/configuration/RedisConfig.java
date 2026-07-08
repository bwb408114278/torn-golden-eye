package pn.torn.goldeneye.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Redis 配置类
 *
 * @author Bai
 * @version 1.2.9
 * @since 2026.07.07
 */
@Configuration
public class RedisConfig {
    /**
     * StringRedisTemplate，用于 Bitmap 操作（SetBit / BitCount）
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * 活跃度采集专用线程池，与主业务线程池隔离，避免阻塞 1 分钟定时任务
     */
    @Bean("activityCollectExecutor")
    public ThreadPoolTaskExecutor activityCollectExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setVirtualThreads(true);
        executor.setMaxPoolSize(200);
        executor.setThreadNamePrefix("activity-collect-");
        return executor;
    }
}
