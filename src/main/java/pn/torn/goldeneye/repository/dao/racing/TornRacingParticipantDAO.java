package pn.torn.goldeneye.repository.dao.racing;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.mapper.racing.TornRacingParticipantMapper;
import pn.torn.goldeneye.repository.model.racing.TornRacingParticipantDO;

import java.util.Collection;
import java.util.List;

/**
 * Torn赛车选手明细表持久层类
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Repository
public class TornRacingParticipantDAO extends ServiceImpl<TornRacingParticipantMapper, TornRacingParticipantDO> {
    /**
     * 按赛事ID查询全部参赛选手。
     *
     * @param raceId Torn赛事ID
     * @return 参赛选手明细
     */
    public List<TornRacingParticipantDO> queryByRaceId(long raceId) {
        return lambdaQuery()
                .eq(TornRacingParticipantDO::getRaceId, raceId)
                .list();
    }

    /**
     * 按赛事ID批量查询全部参赛选手，供个人成绩回算各场名次。
     *
     * @param raceIdList Torn赛事ID集合
     * @return 参赛选手明细；入参为空时返回空列表
     */
    public List<TornRacingParticipantDO> queryByRaceIdList(Collection<Long> raceIdList) {
        if (CollectionUtils.isEmpty(raceIdList)) {
            return List.of();
        }

        return lambdaQuery()
                .in(TornRacingParticipantDO::getRaceId, raceIdList)
                .list();
    }

    /**
     * 查询指定用户在家族赛事中的最近若干场明细。
     *
     * @param userId 目标选手Torn用户ID
     * @param limit  场次上限
     * @return 按赛事ID降序的明细
     */
    public List<TornRacingParticipantDO> queryRecentAllianceByUser(long userId, int limit) {
        return lambdaQuery()
                .eq(TornRacingParticipantDO::getUserId, userId)
                .eq(TornRacingParticipantDO::getIsAlliance, true)
                .orderByDesc(TornRacingParticipantDO::getRaceId)
                .last("LIMIT " + limit)
                .list();
    }
}
