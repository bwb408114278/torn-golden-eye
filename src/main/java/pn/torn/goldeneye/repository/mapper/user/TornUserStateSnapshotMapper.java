package pn.torn.goldeneye.repository.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import pn.torn.goldeneye.repository.model.user.TornUserStateSnapshotDO;

/**
 * Torn用户数据快照数据库访问层
 *
 * @author Bai
 * @version 1.1.5
 * @since 2026.05.22
 */
@Mapper
public interface TornUserStateSnapshotMapper extends BaseMapper<TornUserStateSnapshotDO> {
}