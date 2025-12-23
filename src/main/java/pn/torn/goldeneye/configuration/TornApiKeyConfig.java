package pn.torn.goldeneye.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Torn Api Key配置类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.08.21
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class TornApiKeyConfig {
    private final TornApiKeyDAO keyDao;
    private final ReentrantLock lock = new ReentrantLock();
    /**
     * 存储所有API Key的映射（Key ID -> Key对象）
     */
    private final ConcurrentHashMap<Long, TornApiKeyDO> allKeys = new ConcurrentHashMap<>();
    /**
     * 帮派ID到API Key列表的映射
     */
    private final ConcurrentHashMap<Long, PriorityBlockingQueue<TornApiKeyDO>> factionKeysMap = new ConcurrentHashMap<>();
    /**
     * 用户到API Key的映射
     */
    private final ConcurrentHashMap<Long, TornApiKeyDO> userKeyMap = new ConcurrentHashMap<>();
    /**
     * 正在使用的Key ID集合
     */
    private final Set<Long> inUseKeyIds = ConcurrentHashMap.newKeySet();

    /**
     * 获取Key，返回使用次数最少的
     */
    public TornApiKeyDO getEnableKey() {
        lock.lock();
        try {
            Optional<TornApiKeyDO> minKeyOpt = allKeys.values().stream()
                    .filter(key -> !inUseKeyIds.contains(key.getId()))
                    .min(Comparator.comparingInt(TornApiKeyDO::getUseCount));

            if (minKeyOpt.isPresent()) {
                TornApiKeyDO key = minKeyOpt.get();
                inUseKeyIds.add(key.getId());
                return key;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取所有可用的Key列表
     */
    public List<TornApiKeyDO> getAllEnableKeys() {
        return new ArrayList<>(allKeys.values());
    }

    /**
     * 获取指定帮派的所有Key列表
     */
    public List<TornApiKeyDO> getFactionKeyList(long factionId) {
        PriorityBlockingQueue<TornApiKeyDO> queue = factionKeysMap.get(factionId);
        return queue != null ? new ArrayList<>(queue) : List.of();
    }

    /**
     * 添加新的API Key
     */
    public void addApiKey(TornApiKeyDO apiKey) {
        keyDao.save(apiKey);
        lock.lock();
        try {
            addKeyToMaps(apiKey);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 更新已存在的API Key
     */
    public void updateApiKey(TornApiKeyDO existingKey, TornApiKeyDO newKey) {
        newKey.setUseCount(existingKey.getUseCount());
        newKey.setId(existingKey.getId());
        keyDao.updateById(newKey);

        lock.lock();
        try {
            updateKeyInMemory(existingKey, newKey);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 刷新API KEY数据
     */
    public void reloadKeyData() {
        lock.lock();
        try {
            allKeys.clear();
            factionKeysMap.clear();
            userKeyMap.clear();
            // 注意：不清除inUseKeyIds，因为可能有线程正在使用key。
            // 在归还或废弃时，会从中移除。

            List<TornApiKeyDO> keyList = keyDao.list();
            keyList.forEach(this::addKeyToMaps);
            log.info("成功从数据库重新加载了 {} 个API Key到内存", keyList.size());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取帮派可用的Api Key
     *
     * @param factionId         帮派ID
     * @param needFactionAccess 是否需要帮派权限
     */
    public TornApiKeyDO getFactionKey(long factionId, boolean needFactionAccess) {
        lock.lock();
        try {
            PriorityBlockingQueue<TornApiKeyDO> factionQueue = factionKeysMap.get(factionId);
            if (factionQueue == null || factionQueue.isEmpty()) {
                return null;
            }

            // 从队列中找到一个未被使用的Key
            List<TornApiKeyDO> tempHolder = new ArrayList<>();
            TornApiKeyDO selectedKey = null;

            while (!factionQueue.isEmpty()) {
                TornApiKeyDO key = factionQueue.poll();
                if (!inUseKeyIds.contains(key.getId())) {
                    if (!needFactionAccess || Boolean.TRUE.equals(key.getHasFactionAccess())) {
                        selectedKey = key;
                        break;
                    }
                }
                tempHolder.add(key);
            }

            // 将未选中的key放回队列
            factionQueue.addAll(tempHolder);
            if (selectedKey != null) {
                inUseKeyIds.add(selectedKey.getId());
            }

            return selectedKey;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取用户对应的Api Key
     */
    public TornApiKeyDO getKeyByUserId(long userId) {
        TornApiKeyDO key = userKeyMap.get(userId);
        if (key != null && !inUseKeyIds.contains(key.getId())) {
            inUseKeyIds.add(key.getId());
            return key;
        }
        return null;
    }

    /**
     * 将Key标记为不再使用并更新使用计数
     */
    public void returnKey(TornApiKeyDO key) {
        if (key == null) return;

        lock.lock();
        try {
            if (inUseKeyIds.remove(key.getId())) {
                // 更新使用计数
                key.setUseCount(key.getUseCount() + 1);
                keyDao.lambdaUpdate()
                        .set(TornApiKeyDO::getUseCount, key.getUseCount())
                        .eq(TornApiKeyDO::getId, key.getId())
                        .update();

                // 更新内存中的Key信息
                TornApiKeyDO existingKey = allKeys.get(key.getId());
                if (existingKey != null) {
                    existingKey.setUseCount(key.getUseCount());
                }

                // 将Key重新添加到队列中
                addKeyToQueues(key);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将Key添加到映射和队列中
     */
    private void addKeyToMaps(TornApiKeyDO apiKey) {
        allKeys.put(apiKey.getId(), apiKey);
        userKeyMap.put(apiKey.getUserId(), apiKey);
        addKeyToQueues(apiKey);
    }

    /**
     * 将Key添加到队列中
     */
    private void addKeyToQueues(TornApiKeyDO apiKey) {
        PriorityBlockingQueue<TornApiKeyDO> factionQueue = factionKeysMap.computeIfAbsent(
                apiKey.getFactionId(),
                k -> new PriorityBlockingQueue<>(32, Comparator.comparingInt(TornApiKeyDO::getUseCount))
        );

        // 移除已存在的相同Key（如果存在），以防重复
        factionQueue.removeIf(k -> k.getId().equals(apiKey.getId()));
        // 添加Key到队列
        factionQueue.offer(apiKey);
    }

    /**
     * 移除无效的API Key
     */
    public void invalidateKey(TornApiKeyDO invalidKey) {
        if (invalidKey == null) return;

        lock.lock();
        try {
            log.info("开始处理失效的API Key, ID: {}, Key: {}", invalidKey.getId(), invalidKey.getApiKey());
            // 1. 从数据库中删除
            keyDao.removeById(invalidKey.getId());
            log.info("已从数据库中删除失效的Key, ID: {}", invalidKey.getId());

            // 2. 从正在使用的集合中移除（以防万一）
            inUseKeyIds.remove(invalidKey.getId());

            // 3. 立即刷新内存中的所有Key数据
            log.info("开始刷新内存中的Key数据...");
            reloadKeyData();
            log.info("内存中的Key数据已刷新完毕");

        } finally {
            lock.unlock();
        }
    }

    /**
     * 更新内存中的Key信息
     */
    private void updateKeyInMemory(TornApiKeyDO oldKey, TornApiKeyDO newKey) {
        // 更新 allKeys 映射
        allKeys.put(newKey.getId(), newKey);
        userKeyMap.remove(oldKey.getUserId());
        userKeyMap.put(newKey.getUserId(), newKey);

        // 更新帮派队列
        // 从旧帮派队列移除
        if (oldKey.getFactionId() != null) {
            PriorityBlockingQueue<TornApiKeyDO> oldFactionQueue = factionKeysMap.get(oldKey.getFactionId());
            if (oldFactionQueue != null) {
                oldFactionQueue.removeIf(k -> k.getId().equals(oldKey.getId()));
            }
        }

        // 添加到新帮派队列
        addKeyToQueues(newKey);
    }
}