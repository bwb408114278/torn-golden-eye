package pn.torn.goldeneye.repository.dao.setting;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.setting.TornApiKeyMapper;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;

import java.time.LocalDate;
import java.util.List;

/**
 * Torn Api Key持久层类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@Repository
public class TornApiKeyDAO extends ServiceImpl<TornApiKeyMapper, TornApiKeyDO> {
    /**
     * 通过日期查询
     */
    public List<TornApiKeyDO> queryListByDate(LocalDate date) {
        return lambdaQuery().eq(TornApiKeyDO::getUseDate, date).list();
    }
}