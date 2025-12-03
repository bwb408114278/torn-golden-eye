package pn.torn.goldeneye.torn.manager.user;

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
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户公共逻辑层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.10.10
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TornUserManager implements DataCacheManager {
    private final TornUserDAO userDao;
    @Lazy
    @Resource
    private TornUserManager userManager;

    @Override
    public void warmUpCache() {
        userManager.getUserMap();
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_USER, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_USER_QQ, allEntries = true),
            @CacheEvict(cacheNames = CacheConstants.KEY_TORN_USER_MAP, allEntries = true)})
    public void refreshCache() {
        log.info("用户缓存已重置");
    }

    @Cacheable(value = CacheConstants.KEY_TORN_USER)
    public TornUserDO getUserById(long id) {
        return userDao.getById(id);
    }

    @Cacheable(value = CacheConstants.KEY_TORN_USER_QQ)
    public TornUserDO getUserByQq(long qqId) {
        return userDao.lambdaQuery().eq(TornUserDO::getQqId, qqId).one();
    }

    @Cacheable(value = CacheConstants.KEY_TORN_USER_MAP)
    public Map<Long, TornUserDO> getUserMap() {
        List<TornUserDO> userList = userDao.lambdaQuery().list();
        Map<Long, TornUserDO> resultMap = HashMap.newHashMap(userList.size());
        userList.forEach(u -> resultMap.put(u.getId(), u));
        return resultMap;
    }
}