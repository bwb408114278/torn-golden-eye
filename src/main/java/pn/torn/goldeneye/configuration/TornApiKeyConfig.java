package pn.torn.goldeneye.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Torn Api Key配置类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.21
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class TornApiKeyConfig {
    private final DynamicTaskService taskService;
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
     * QQ到API Key的映射
     */
    private final ConcurrentHashMap<Long, TornApiKeyDO> qqKeyMap = new ConcurrentHashMap<>();
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
        lock.lock();
        try {
            return new ArrayList<>(allKeys.values());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取指定帮派的所有Key列表
     */
    public List<TornApiKeyDO> getFactionKeyList(long factionId) {
        lock.lock();
        try {
            PriorityBlockingQueue<TornApiKeyDO> queue = factionKeysMap.get(factionId);
            return queue != null ? new ArrayList<>(queue) : List.of();
        } finally {
            lock.unlock();
        }
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
    public void refreshKeyData() {
        lock.lock();
        try {
            allKeys.clear();
            factionKeysMap.clear();
            qqKeyMap.clear();
            inUseKeyIds.clear();

            LocalDate queryDate = LocalTime.now().isAfter(LocalTime.of(8, 0)) ?
                    LocalDate.now() : LocalDate.now().plusDays(-1);

            List<TornApiKeyDO> keyList = keyDao.queryListByDate(queryDate);
            if (CollectionUtils.isEmpty(keyList)) {
                keyList = keyDao.lambdaQuery().eq(TornApiKeyDO::getUseDate, queryDate.plusDays(-1)).list();
                List<TornApiKeyDO> newList = keyList.stream().map(TornApiKeyDO::copyNewData).toList();
                keyDao.saveBatch(newList);
                keyList = newList;
            }

            // 将所有Key添加到映射中
            for (TornApiKeyDO key : keyList) {
                addKeyToMaps(key);
            }

            taskService.updateTask("refresh-api", this::refreshKeyData,
                    LocalDate.now().atTime(8, 1, 0).plusDays(1));
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

            TornApiKeyDO key;
            if (needFactionAccess) {
                key = findKeyWithAccessInQueue(factionQueue);
            } else {
                key = factionQueue.poll();
            }

            if (key != null) {
                inUseKeyIds.add(key.getId());
            }

            return key;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取QQ对应的Api Key
     *
     * @param qqId QQ号
     */
    public TornApiKeyDO getKeyByQqId(long qqId) {
        lock.lock();
        try {
            TornApiKeyDO key = qqKeyMap.get(qqId);
            if (key != null) {
                inUseKeyIds.add(key.getId());
            }
            return key;
        } finally {
            lock.unlock();
        }
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

        // 添加到QQ映射
        if (apiKey.getQqId() != null) {
            qqKeyMap.put(apiKey.getQqId(), apiKey);
        }

        // 添加到帮派队列
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

        // 移除已存在的相同Key（如果存在）
        factionQueue.removeIf(k -> k.getId().equals(apiKey.getId()));

        // 添加Key到队列
        if (!factionQueue.offer(apiKey)) {
            log.error("添加Key到帮派队列失败, 帮派ID: {}, Key: {}", apiKey.getFactionId(), apiKey);
            // 重试机制
            int retryCount = 0;
            while (retryCount < 3 && !factionQueue.offer(apiKey)) {
                log.warn("第{}次重试将Key添加到帮派队列{}: {}", retryCount + 1, apiKey.getFactionId(), apiKey);
                retryCount++;
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (retryCount >= 3) {
                throw new BizException("Api Key队列添加失败");
            }
        }
    }

    /**
     * 在队列中查找具有帮派权限的Key
     */
    private TornApiKeyDO findKeyWithAccessInQueue(PriorityBlockingQueue<TornApiKeyDO> queue) {
        // 由于PriorityBlockingQueue不支持遍历，我们需要临时取出并放回
        List<TornApiKeyDO> tempList = new ArrayList<>();
        TornApiKeyDO foundKey = null;

        while (!queue.isEmpty()) {
            TornApiKeyDO key = queue.poll();
            if (Boolean.TRUE.equals(key.getHasFactionAccess())) {
                foundKey = key;
                break;
            }
            tempList.add(key);
        }

        // 将取出的Key放回队列
        for (TornApiKeyDO key : tempList) {
            queue.offer(key);
        }

        return foundKey;
    }

    /**
     * 更新内存中的Key信息
     */
    private void updateKeyInMemory(TornApiKeyDO oldKey, TornApiKeyDO newKey) {
        // 更新所有Keys映射
        TornApiKeyDO existingKey = allKeys.get(oldKey.getId());
        if (existingKey != null) {
            existingKey.setHasFactionAccess(newKey.getHasFactionAccess());
            existingKey.setApiKey(newKey.getApiKey());
            existingKey.setFactionId(newKey.getFactionId());
            existingKey.setQqId(newKey.getQqId());
            existingKey.setUseCount(newKey.getUseCount());
        }

        // 更新QQ映射
        if (oldKey.getQqId() != null) {
            qqKeyMap.remove(oldKey.getQqId());
        }
        if (newKey.getQqId() != null) {
            qqKeyMap.put(newKey.getQqId(), newKey);
        }

        // 更新帮派队列
        PriorityBlockingQueue<TornApiKeyDO> oldFactionQueue = factionKeysMap.get(oldKey.getFactionId());
        if (oldFactionQueue != null) {
            oldFactionQueue.removeIf(k -> k.getId().equals(oldKey.getId()));
        }

        // 如果帮派ID发生变化，需要从旧帮派队列移除，添加到新帮派队列
        if (!Objects.equals(oldKey.getFactionId(), newKey.getFactionId())) {
            PriorityBlockingQueue<TornApiKeyDO> newFactionQueue = factionKeysMap.computeIfAbsent(
                    newKey.getFactionId(),
                    k -> new PriorityBlockingQueue<>(32, Comparator.comparingInt(TornApiKeyDO::getUseCount))
            );
            newFactionQueue.offer(newKey);
        } else {
            // 帮派ID未变化，直接添加到原队列
            addKeyToQueues(newKey);
        }
    }
}