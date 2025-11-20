package pn.torn.goldeneye.torn.manager.torn;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.repository.dao.torn.TornItemsDAO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;

import java.util.Comparator;
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
    @Lazy
    @Resource
    private TornItemsManager itemsManager;

    @Override
    public void warmUpCache() {
        itemsManager.getList();
        itemsManager.getMap();
        itemsManager.getNameMap();
        itemsManager.getSortItemNameList();
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_ITEM, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_ITEM_MAP, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_ITEM_NAME_MAP, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_ITEM_NAME_SORT_LIST, allEntries = true)})
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

    @Cacheable(value = CacheConstants.KEY_TORN_ITEM_NAME_MAP)
    public Map<String, TornItemsDO> getNameMap() {
        List<TornItemsDO> list = itemsDao.list();
        Map<String, TornItemsDO> map = new HashMap<>();
        list.forEach(i -> map.put(i.getItemName(), i));
        return map;
    }

    /**
     * 根据长度倒序排序，例如Small First Aid Kit排在First Aid Kit之前
     */
    @Cacheable(value = CacheConstants.KEY_TORN_ITEM_NAME_SORT_LIST)
    public List<String> getSortItemNameList() {
        return itemsDao.list().stream()
                .map(TornItemsDO::getItemName)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }
}