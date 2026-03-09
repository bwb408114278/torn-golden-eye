package pn.torn.goldeneye.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Torn Api Key配置类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.08.21
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class TornApiKeyConfig {
    private final TornApiKeyDAO keyDao;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    /**
     * 存储所有API Key的映射（Key ID -> Key对象）
     */
    private final ConcurrentHashMap<Long, TornApiKeyDO> allKeys = new ConcurrentHashMap<>();
    /**
     * 帮派ID到API Key列表的映射
     */
    private final ConcurrentHashMap<Long, Set<Long>> factionKeysMap = new ConcurrentHashMap<>();
    /**
     * 用户到API Key的映射
     */
    private final ConcurrentHashMap<Long, Long> userKeyMap = new ConcurrentHashMap<>();
    /**
     * 正在使用的Key ID集合
     */
    private final Set<Long> inUseKeyIds = ConcurrentHashMap.newKeySet();

    /**
     * 获取Key，返回使用次数最少的
     */
    public TornApiKeyDO getEnableKey() {
        lock.readLock().lock();
        try {
            return allKeys.values().stream()
                    .filter(key -> !inUseKeyIds.contains(key.getId()))
                    .min(Comparator.comparingInt(TornApiKeyDO::getUseCount))
                    .map(this::markKeyInUse)
                    .orElse(null);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取所有可用的Key列表
     */
    public List<TornApiKeyDO> getAllEnableKeys() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(allKeys.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 添加新的API Key
     */
    public void addApiKey(TornApiKeyDO apiKey) {
        keyDao.save(apiKey);
        lock.writeLock().lock();
        try {
            addKeyToMaps(apiKey);
            log.info("成功添加API Key, ID: {}, 用户ID: {}, 帮派ID: {}",
                    apiKey.getId(), apiKey.getUserId(), apiKey.getFactionId());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 更新已存在的API Key
     */
    public void updateApiKey(TornApiKeyDO existingKey, TornApiKeyDO newKey) {
        newKey.setUseCount(existingKey.getUseCount());
        newKey.setId(existingKey.getId());
        keyDao.updateById(newKey);
        lock.writeLock().lock();
        try {
            updateKeyInMemory(existingKey, newKey);
            log.info("成功更新API Key, ID: {}", newKey.getId());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 刷新API KEY数据
     */
    public void reloadKeyData() {
        lock.writeLock().lock();
        try {
            clearAllMaps();

            List<TornApiKeyDO> keyList = keyDao.list();
            keyList.forEach(this::addKeyToMaps);

            log.info("成功从数据库重新加载了 {} 个API Key到内存", keyList.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取帮派可用的Api Key
     *
     * @param factionId         帮派ID
     * @param needFactionAccess 是否需要帮派权限
     */
    public TornApiKeyDO getFactionKey(long factionId, boolean needFactionAccess) {
        lock.readLock().lock();
        try {
            Set<Long> keyIds = factionKeysMap.get(factionId);
            if (keyIds == null || keyIds.isEmpty()) {
                return null;
            }
            return keyIds.stream()
                    .map(allKeys::get)
                    .filter(Objects::nonNull)
                    .filter(key -> !inUseKeyIds.contains(key.getId()))
                    .filter(key -> !needFactionAccess || Boolean.TRUE.equals(key.getHasFactionAccess()))
                    .min(Comparator.comparingInt(TornApiKeyDO::getUseCount))
                    .map(this::markKeyInUse)
                    .orElse(null);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取用户对应的Api Key
     */
    public TornApiKeyDO getKeyByUserId(long userId) {
        lock.readLock().lock();
        try {
            Long keyId = userKeyMap.get(userId);
            if (keyId == null) {
                return null;
            }

            TornApiKeyDO key = allKeys.get(keyId);
            if (key != null && !inUseKeyIds.contains(keyId)) {
                return markKeyInUse(key);
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 将Key标记为不再使用并更新使用计数
     */
    public void returnKey(TornApiKeyDO key) {
        if (key == null || key.getId() == null || key.getId().equals(0L)) {
            return;
        }
        if (!inUseKeyIds.remove(key.getId())) {
            return;
        }
        lock.readLock().lock();
        try {
            incrementKeyUsageCount(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 移除无效的API Key
     */
    public void invalidateKey(TornApiKeyDO invalidKey) {
        if (invalidKey == null || invalidKey.getId() == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            log.info("开始处理失效的API Key, ID: {}", invalidKey.getId());

            keyDao.removeById(invalidKey.getId());
            inUseKeyIds.remove(invalidKey.getId());

            reloadKeyDataInternal();

            log.info("失效的API Key已处理完毕, ID: {}", invalidKey.getId());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 标记Key为使用中
     */
    private TornApiKeyDO markKeyInUse(TornApiKeyDO key) {
        inUseKeyIds.add(key.getId());
        return key;
    }

    /**
     * 增加Key使用计数
     */
    private void incrementKeyUsageCount(TornApiKeyDO key) {
        int newCount = key.getUseCount() + 1;
        key.setUseCount(newCount);

        keyDao.lambdaUpdate()
                .set(TornApiKeyDO::getUseCount, newCount)
                .eq(TornApiKeyDO::getId, key.getId())
                .update();
        TornApiKeyDO existingKey = allKeys.get(key.getId());
        if (existingKey != null) {
            existingKey.setUseCount(newCount);
        }
    }

    /**
     * 将Key添加到映射中
     */
    private void addKeyToMaps(TornApiKeyDO apiKey) {
        if (apiKey == null || apiKey.getId() == null) {
            return;
        }

        allKeys.put(apiKey.getId(), apiKey);
        userKeyMap.put(apiKey.getUserId(), apiKey.getId());

        if (apiKey.getFactionId() != null) {
            factionKeysMap.computeIfAbsent(apiKey.getFactionId(), k -> ConcurrentHashMap.newKeySet())
                    .add(apiKey.getId());
        }
    }

    /**
     * 更新内存中的Key信息
     */
    private void updateKeyInMemory(TornApiKeyDO oldKey, TornApiKeyDO newKey) {
        allKeys.put(newKey.getId(), newKey);

        userKeyMap.remove(oldKey.getUserId());
        userKeyMap.put(newKey.getUserId(), newKey.getId());
        if (oldKey.getFactionId() != null) {
            Set<Long> oldFactionKeys = factionKeysMap.get(oldKey.getFactionId());
            if (oldFactionKeys != null) {
                oldFactionKeys.remove(oldKey.getId());
            }
        }
        if (newKey.getFactionId() != null) {
            factionKeysMap.computeIfAbsent(newKey.getFactionId(), k -> ConcurrentHashMap.newKeySet())
                    .add(newKey.getId());
        }
    }

    /**
     * 清空所有映射
     */
    private void clearAllMaps() {
        allKeys.clear();
        factionKeysMap.clear();
        userKeyMap.clear();
    }

    /**
     * 内部重载方法（已持有写锁）
     */
    private void reloadKeyDataInternal() {
        clearAllMaps();
        List<TornApiKeyDO> keyList = keyDao.list();
        keyList.forEach(this::addKeyToMaps);
    }
}