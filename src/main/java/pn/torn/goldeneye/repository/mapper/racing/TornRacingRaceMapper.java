package pn.torn.goldeneye.repository.mapper.racing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.racing.TornRacingRaceDO;

/**
 * Torn赛车赛事主表数据库访问层
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Mapper
public interface TornRacingRaceMapper extends BaseMapper<TornRacingRaceDO> {
}
