package pn.torn.goldeneye.repository.mapper.racing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.racing.TornRacingParticipantDO;

/**
 * Torn赛车选手明细表数据库访问层
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Mapper
public interface TornRacingParticipantMapper extends BaseMapper<TornRacingParticipantDO> {
}
