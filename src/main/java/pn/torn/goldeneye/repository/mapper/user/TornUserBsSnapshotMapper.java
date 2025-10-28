package pn.torn.goldeneye.repository.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.user.TornUserBsSnapshotDO;

/**
 * Torn用户BS快照数据库访问层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.27
 */
@Mapper
public interface TornUserBsSnapshotMapper extends BaseMapper<TornUserBsSnapshotDO> {
}