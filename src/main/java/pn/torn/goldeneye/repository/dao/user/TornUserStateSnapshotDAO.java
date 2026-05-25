package pn.torn.goldeneye.repository.dao.user;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.user.TornUserStateSnapshotMapper;
import pn.torn.goldeneye.repository.model.user.TornUserStateSnapshotDO;

/**
 * Torn用户数据快照持久层类
 *
 * @author Bai
 * @version 1.1.5
 * @since 2026.05.22
 */
@Repository
public class TornUserStateSnapshotDAO extends ServiceImpl<TornUserStateSnapshotMapper, TornUserStateSnapshotDO> {
}