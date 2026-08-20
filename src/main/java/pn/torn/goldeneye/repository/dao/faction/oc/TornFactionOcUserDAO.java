package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcUserMapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;

import java.util.Collection;
import java.util.List;

/**
 * Torn Oc User持久层类
 *
 * @author Bai
 * @version 1.3.6
 * @since 2025.07.29
 */
@Repository
public class TornFactionOcUserDAO extends ServiceImpl<TornFactionOcUserMapper, TornFactionOcUserDO> {
    /**
     * 更新用户帮派
     *
     * @param factionId 帮派ID
     * @param userId    用户ID
     */
    public void updateUserFaction(long factionId, long userId) {
        lambdaUpdate()
                .set(TornFactionOcUserDO::getFactionId, factionId)
                .eq(TornFactionOcUserDO::getUserId, userId)
                .update();
    }

    /**
     * 通过用户ID查询
     *
     * @param userId 用户ID
     */
    public List<TornFactionOcUserDO> queryByUserId(long userId) {
        return lambdaQuery().eq(TornFactionOcUserDO::getUserId, userId).list();
    }

    /**
     * 通过用户ID查询
     */
    public List<TornFactionOcUserDO> queryByUserId(List<Long> userIdList) {
        if (CollectionUtils.isEmpty(userIdList)) {
            return List.of();
        }

        return lambdaQuery().in(TornFactionOcUserDO::getUserId, userIdList).list();
    }

    /**
     * 通过帮派ID查询
     *
     * @param factionId 帮派ID
     */
    public List<TornFactionOcUserDO> queryByFactionId(long factionId) {
        return lambdaQuery().eq(TornFactionOcUserDO::getFactionId, factionId).list();
    }

    /**
     * 按帮派和用户集合批量查询用户成功率数据。
     *
     * @param factionId 帮派ID
     * @param userIds   用户ID集合
     * @return 指定帮派和用户的成功率数据
     */
    public List<TornFactionOcUserDO> queryByFactionIdAndUserIds(long factionId, Collection<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return List.of();
        }

        return lambdaQuery()
                .eq(TornFactionOcUserDO::getFactionId, factionId)
                .in(TornFactionOcUserDO::getUserId, userIds)
                .list();
    }
}
