package pn.torn.goldeneye.repository.dao.faction.oc;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.oc.TornFactionOcUserMapper;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;

import java.util.List;

/**
 * Torn Oc User持久层类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Repository
public class TornFactionOcUserDAO extends ServiceImpl<TornFactionOcUserMapper, TornFactionOcUserDO> {
    /**
     * 通过用户和级别查询列表
     *
     * @param userId 用户ID
     * @param rank   OC级别
     */
    public List<TornFactionOcUserDO> queryListByUserAndRank(long userId, int rank) {
        return lambdaQuery()
                .eq(TornFactionOcUserDO::getUserId, userId)
                .eq(TornFactionOcUserDO::getRank, rank)
                .list();
    }
}