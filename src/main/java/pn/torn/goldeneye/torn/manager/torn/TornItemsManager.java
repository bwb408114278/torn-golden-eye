package pn.torn.goldeneye.torn.manager.torn;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.repository.dao.torn.TornItemsDAO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Torn物品公共逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.09.26
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TornItemsManager implements DataCacheManager {
    private final TornItemsDAO itemsDao;

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_ITEM, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_ITEM_MAP, allEntries = true)})
    public void refreshCache() {
        log.info("物品缓存已重置");
    }

    @Cacheable(value = CacheConstants.KEY_TORN_ITEM)
    public List<TornItemsDO> getList() {
        return itemsDao.list();
    }

    @Cacheable(value = CacheConstants.KEY_TORN_ITEM_MAP)
    public Map<Integer, TornItemsDO> getMap() {
        List<TornItemsDO> list = itemsDao.list();
        Map<Integer, TornItemsDO> map = new HashMap<>();
        list.forEach(i -> map.put(i.getId(), i));
        return map;
    }
}