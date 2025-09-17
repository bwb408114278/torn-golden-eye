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
     * 强制刷新缓存（清除并重新加载）
     */
    void refreshCache();
}