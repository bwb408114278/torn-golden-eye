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
 * @version 1.3.5
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
            List<KeyCandidate> candidates = allKeys.values().stream()
                    .map(this::toCandidate)
                    .toList();
            return selectLeastUsedKey(candidates);
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
            List<KeyCandidate> candidates = keyIds.stream()
                    .map(allKeys::get)
                    .filter(Objects::nonNull)
                    .filter(key -> !needFactionAccess || Boolean.TRUE.equals(key.getHasFactionAccess()))
                    .map(this::toCandidate)
                    .toList();
            return selectLeastUsedKey(candidates);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 将Key从当前帮派Key池中临时移除，数据库记录和全局Key映射保持不变。
     *
     * @param apiKey 需要移除的Key
     */
    public void removeFromFactionPool(TornApiKeyDO apiKey) {
        if (apiKey == null || apiKey.getId() == null || apiKey.getFactionId() == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            Set<Long> factionKeys = factionKeysMap.get(apiKey.getFactionId());
            if (factionKeys != null) {
                factionKeys.remove(apiKey.getId());
            }
        } finally {
            lock.writeLock().unlock();
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
            if (key != null && inUseKeyIds.add(keyId)) {
                return key;
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
        // 使用次数递增会修改排序比较字段, 必须在写锁内完成, 防止排序期间读取到被修改的计数
        lock.writeLock().lock();
        try {
            incrementKeyUsageCount(key);
        } finally {
            lock.writeLock().unlock();
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

    /**
     * 从不可变候选快照中选出使用次数最少的未占用Key
     * <p>
     * 排序只依赖快照值：useCountSnapshot升序，再按keyId升序保证并列时的确定性；
     * 选择与占用登记必须由调用方在同一次读锁临界区内完成，避免两个线程领到同一Key。
     *
     * @param candidates 构建完成的候选Key快照列表
     * @return 使用次数最少的可用Key, 无可用Key时返回null
     */
    private TornApiKeyDO selectLeastUsedKey(List<KeyCandidate> candidates) {
        List<KeyCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparingInt(KeyCandidate::useCountSnapshot)
                        .thenComparingLong(KeyCandidate::keyId))
                .toList();
        for (KeyCandidate candidate : sorted) {
            if (inUseKeyIds.add(candidate.keyId())) {
                return candidate.key();
            }
        }
        return null;
    }

    /**
     * 以Key当前使用次数构建不可变候选快照
     *
     * @param key 池内Key
     * @return 候选快照, 排序期间不受并发计数修改影响
     */
    private KeyCandidate toCandidate(TornApiKeyDO key) {
        return new KeyCandidate(key, key.getUseCount(), key.getId());
    }

    /**
     * Key候选不可变快照
     *
     * @param key              原Key对象
     * @param useCountSnapshot 构建快照时的使用次数
     * @param keyId            Key ID
     */
    private record KeyCandidate(TornApiKeyDO key, int useCountSnapshot, long keyId) {
    }
}
