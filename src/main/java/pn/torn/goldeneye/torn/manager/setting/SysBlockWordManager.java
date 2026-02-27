package pn.torn.goldeneye.torn.manager.setting;

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
import pn.torn.goldeneye.repository.dao.setting.SysBlockWordDAO;
import pn.torn.goldeneye.repository.model.setting.SysBlockWordDO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 屏蔽词设置公共逻辑层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.25
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SysBlockWordManager implements DataCacheManager {
    private final SysBlockWordDAO blockWordDao;
    @Lazy
    @Resource
    private SysBlockWordManager blockWordManager;

    @Override
    public void warmUpCache() {
        blockWordManager.getWordMap();
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.KEY_SYS_BLOCK_WORD, allEntries = true)})
    public void refreshCache() {
        log.info("屏蔽词设置缓存已重置");
    }

    @Cacheable(value = CacheConstants.KEY_SYS_BLOCK_WORD)
    public Map<String, SysBlockWordDO> getWordMap() {
        List<SysBlockWordDO> list = blockWordDao.list();
        Map<String, SysBlockWordDO> resultMap = new HashMap<>();
        for (SysBlockWordDO word : list) {
            resultMap.put(word.getWord(), word);
        }

        return resultMap;
    }
}