package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcUserMapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Torn Oc User持久层类
 *
 * @author Bai
 * @version 1.9.3
 * @since 2025.07.29
 */
@Repository
public class TornFactionOcUserDAO extends ServiceImpl<TornFactionOcUserMapper, TornFactionOcUserDO> {
    /**
     * 全局默认行使用的帮派ID。
     */
    private static final long GLOBAL_FACTION_ID = 0L;

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
     * 查询推荐口径使用的能力矩阵：帮派行优先，帮派未覆盖的（OC名称, 等级, 岗位）键回退全局默认行。
     *
     * <p>torn_faction_oc_user 既有 faction_id 为 0 的全局默认行，也有具体帮派行。替代概率必须在
     * 帮派口径下计算，因此按（OC名称, 等级, 岗位）键取并集：帮派已经覆盖的键完全使用帮派行，
     * 只有帮派完全没有该键时才用全局默认行兜底，避免把无关帮派的成员算进本帮派的可填人数。</p>
     *
     * @param factionId 帮派ID
     * @return 帮派行加上未被覆盖的全局默认行
     */
    public List<TornFactionOcUserDO> queryFactionMatrix(long factionId) {
        List<TornFactionOcUserDO> rows = lambdaQuery()
                .in(TornFactionOcUserDO::getFactionId, List.of(GLOBAL_FACTION_ID, factionId))
                .list();
        Set<String> factionKeys = rows.stream()
                .filter(row -> row.getFactionId() != null && row.getFactionId() == factionId)
                .map(this::matrixKey)
                .collect(Collectors.toSet());
        List<TornFactionOcUserDO> matrix = rows.stream()
                .filter(row -> row.getFactionId() != null
                        && (row.getFactionId() == factionId || !factionKeys.contains(matrixKey(row))))
                .toList();
        return List.copyOf(matrix);
    }

    /**
     * 构造能力矩阵键：OC名称、等级与岗位短编码共同定位一个岗位。
     */
    private String matrixKey(TornFactionOcUserDO row) {
        return row.getOcName() + "|" + row.getRank() + "|" + row.getPosition();
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
