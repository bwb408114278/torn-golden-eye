package pn.torn.goldeneye.repository.dao.user;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.user.TornUserBsSnapshotMapper;
import pn.torn.goldeneye.repository.model.user.TornUserBsSnapshotDO;

/**
 * Torn用户BS快照持久层类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.27
 */
@Repository
public class TornUserBsSnapshotDAO extends ServiceImpl<TornUserBsSnapshotMapper, TornUserBsSnapshotDO> {
}