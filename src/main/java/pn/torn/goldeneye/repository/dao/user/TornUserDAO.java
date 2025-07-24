package pn.torn.goldeneye.repository.dao.user;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.user.TornUserMapper;
import pn.torn.goldeneye.repository.model.user.TornUserDO;

/**
 * Torn User持久层类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Repository
public class TornUserDAO extends ServiceImpl<TornUserMapper, TornUserDO> {
}