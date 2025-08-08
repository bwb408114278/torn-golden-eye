package pn.torn.goldeneye.repository.dao.setting;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.setting.TornApiKeyMapper;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;

/**
 * Torn Api Key持久层类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@Repository
public class TornApiKeyDAO extends ServiceImpl<TornApiKeyMapper, TornApiKeyDO> {
}