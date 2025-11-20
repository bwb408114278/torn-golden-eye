package pn.torn.goldeneye.base.cache;

/**
 * 数据缓存约束
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.17
 */
public interface DataCacheManager {
    /**
     * 预热缓存
     * 在应用启动时调用，提前加载常用数据到缓存
     */
    default void warmUpCache() {
        // 默认空实现，子类按需重写
    }

    /**
     * 强制刷新缓存（清除并重新加载）
     */
    void refreshCache();
}