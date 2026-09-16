package pn.torn.goldeneye.repository.mapper.faction.attack;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwRankDO;

/**
 * RW真赛战神榜名次结算数据库访问层
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@Mapper
public interface TornFactionRwRankMapper extends BaseMapper<TornFactionRwRankDO> {
    /**
     * 物理删除指定RW的全部结算行
     * <p>
     * MyBatis-Plus逻辑删除会把旧行标记为已删除但仍占用 (rw_id, user_id) 唯一索引，
     * 因此重结算前必须物理删除，保证同一场战争可重复结算。
     *
     * @param rwId RW ID
     * @return 实际删除行数
     */
    int physicalDeleteByRwId(@Param("rwId") long rwId);
}
