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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Torn Api Key配置类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.21
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class TornApiKeyConfig {
    private final DynamicTaskService taskService;
    private final TornApiKeyDAO keyDao;
    private final ReentrantLock queueLock = new ReentrantLock();
    private final ConcurrentHashMap<Long, TornApiKeyDO> inUseKeys = new ConcurrentHashMap<>();

    private final PriorityBlockingQueue<TornApiKeyDO> keyQueue =
            new PriorityBlockingQueue<>(32, Comparator.comparingInt(TornApiKeyDO::getUseCount));
    private final PriorityBlockingQueue<TornApiKeyDO> factionKeyQueue =
            new PriorityBlockingQueue<>(32, Comparator.comparingInt(TornApiKeyDO::getUseCount));

    /**
     * 获取可用的Key列表
     */
    public List<TornApiKeyDO> getEnableKeyList() {
        queueLock.lock();
        try {
            return keyQueue.stream().toList();
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * 添加新的API Key
     */
    public void addApiKey(TornApiKeyDO apiKey) {
        keyDao.save(apiKey);

        queueLock.lock();
        try {
            addKeyToQueues(apiKey);
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * 更新已存在的API Key
     */
    public void updateApiKey(TornApiKeyDO existingKey, TornApiKeyDO newKey) {
        newKey.setUseCount(existingKey.getUseCount());
        newKey.setId(existingKey.getId());

        keyDao.updateById(newKey);
        queueLock.lock();
        try {
            updateKeyInMemory(existingKey, newKey);
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * 刷新API KEY数据
     */
    public void refreshKeyData() {
        queueLock.lock();
        try {
            keyQueue.clear();
            factionKeyQueue.clear();
            inUseKeys.clear();

            LocalDate queryDate = LocalTime.now().isAfter(LocalTime.of(8, 0)) ?
                    LocalDate.now() : LocalDate.now().plusDays(-1);

            List<TornApiKeyDO> keyList = keyDao.queryListByDate(queryDate);
            if (CollectionUtils.isEmpty(keyList)) {
                keyList = keyDao.lambdaQuery().eq(TornApiKeyDO::getUseDate, queryDate.plusDays(-1)).list();
                List<TornApiKeyDO> newList = keyList.stream().map(TornApiKeyDO::copyNewData).toList();
                keyDao.saveBatch(newList);
                keyList = newList;
            }

            keyQueue.addAll(keyList);
            factionKeyQueue.addAll(keyList.stream().filter(TornApiKeyDO::getHasFactionAccess).toList());
            taskService.updateTask("refresh-api", this::refreshKeyData,
                    LocalDate.now().atTime(8, 1, 0).plusDays(1));
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * 获取可用的Api Key
     *
     * @param needFactionAccess 是否需要帮派权限
     */
    public TornApiKeyDO getEnableKey(boolean needFactionAccess) {
        queueLock.lock();
        try {
            TornApiKeyDO key = needFactionAccess ? factionKeyQueue.poll() : keyQueue.poll();
            if (key != null) {
                inUseKeys.put(key.getId(), key);
            }
            return key;
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * 将Key返还队列并更新使用计数
     */
    public void returnKeyToQueue(TornApiKeyDO key) {
        if (key == null) return;

        queueLock.lock();
        try {
            TornApiKeyDO usedKey = inUseKeys.remove(key.getId());
            if (usedKey == null) return;

            usedKey.setUseCount(usedKey.getUseCount() + 1);
            keyDao.lambdaUpdate()
                    .set(TornApiKeyDO::getUseCount, usedKey.getUseCount())
                    .eq(TornApiKeyDO::getId, usedKey.getId())
                    .update();
            addKeyToQueues(usedKey);
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * 将Key添加到队列（重构复用）
     */
    private void addKeyToQueues(TornApiKeyDO apiKey) {
        queueLock.lock();
        try {
            offerToQueue(keyQueue, apiKey, "KEY_QUEUE");
            if (Boolean.TRUE.equals(apiKey.getHasFactionAccess())) {
                offerToQueue(factionKeyQueue, apiKey, "FACTION_KEY_QUEUE");
            }
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * 安全地将API Key添加到队列
     */
    private void offerToQueue(PriorityBlockingQueue<TornApiKeyDO> queue, TornApiKeyDO key, String queueName) {
        if (!queue.offer(key)) {
            log.error("添加Key到队列失败, {}: {}", queueName, key);
            // 重试机制
            int retryCount = 0;
            while (retryCount < 3 && !queue.offer(key)) {
                log.warn("第{}次重试将Key添加到队列{}: {}", retryCount + 1, queueName, key);
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
     * 更新内存中的Key信息（队列和inUseKeys）
     */
    private void updateKeyInMemory(TornApiKeyDO oldKey, TornApiKeyDO newKey) {
        if (inUseKeys.containsKey(oldKey.getId())) {
            TornApiKeyDO inUseKey = inUseKeys.get(oldKey.getId());
            inUseKey.setHasFactionAccess(newKey.getHasFactionAccess());
            inUseKey.setApiKey(newKey.getApiKey());
        }

        updateKeyInQueue(keyQueue, oldKey, newKey);
        updateKeyInQueue(factionKeyQueue, oldKey, newKey);
    }

    /**
     * 在指定队列中更新Key
     */
    private void updateKeyInQueue(PriorityBlockingQueue<TornApiKeyDO> queue,
                                  TornApiKeyDO oldKey, TornApiKeyDO newKey) {
        if (queue.removeIf(k -> k.getId().equals(oldKey.getId()))) {
            newKey.setUseCount(oldKey.getUseCount());
            offerToQueue(queue, newKey, queue == factionKeyQueue ? "FACTION_KEY_QUEUE" : "KEY_QUEUE");
        }
    }
}