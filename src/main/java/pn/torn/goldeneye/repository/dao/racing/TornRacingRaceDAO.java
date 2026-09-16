package pn.torn.goldeneye.repository.dao.racing;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.mapper.racing.TornRacingRaceMapper;
import pn.torn.goldeneye.repository.model.racing.TornRacingRaceDO;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Torn赛车赛事主表持久层类
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Repository
public class TornRacingRaceDAO extends ServiceImpl<TornRacingRaceMapper, TornRacingRaceDO> {
    /**
     * 判断指定业务日期是否已有赛事主行。
     *
     * @param businessDate 业务日期
     * @return true表示已抓取
     */
    public boolean existsByBusinessDate(LocalDate businessDate) {
        return lambdaQuery()
                .eq(TornRacingRaceDO::getBusinessDate, businessDate)
                .count() > 0;
    }

    /**
     * 按业务日期查询赛事主行，同一天存在多行时取赛事ID最大的一行。
     *
     * @param businessDate 业务日期
     * @return 赛事主行；不存在时返回null
     */
    public TornRacingRaceDO queryByBusinessDate(LocalDate businessDate) {
        List<TornRacingRaceDO> raceList = lambdaQuery()
                .eq(TornRacingRaceDO::getBusinessDate, businessDate)
                .orderByDesc(TornRacingRaceDO::getRaceId)
                .list();
        return raceList.isEmpty() ? null : raceList.getFirst();
    }

    /**
     * 按赛事ID查询赛事主行。
     *
     * @param raceId Torn赛事ID
     * @return 赛事主行；不存在时返回null
     */
    public TornRacingRaceDO queryByRaceId(long raceId) {
        return lambdaQuery()
                .eq(TornRacingRaceDO::getRaceId, raceId)
                .one();
    }

    /**
     * 按赛事ID批量查询赛事主行。
     *
     * @param raceIdList Torn赛事ID集合
     * @return 赛事主行列表；入参为空时返回空列表
     */
    public List<TornRacingRaceDO> queryByRaceIdList(Collection<Long> raceIdList) {
        if (CollectionUtils.isEmpty(raceIdList)) {
            return List.of();
        }

        return lambdaQuery()
                .in(TornRacingRaceDO::getRaceId, raceIdList)
                .list();
    }

    /**
     * 查询业务日期最晚的一场已抓取赛事，作为定位链的动态候选来源。
     *
     * @return 最近一场赛事主行；无数据时返回null
     */
    public TornRacingRaceDO queryLatestRace() {
        return lambdaQuery()
                .orderByDesc(TornRacingRaceDO::getBusinessDate)
                .last("LIMIT 1")
                .one();
    }
}
