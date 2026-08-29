package pn.torn.goldeneye.repository.dao.setting;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.setting.TornSettingOcMapper;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;

import java.util.List;

/**
 * Torn设置OC持久层类
 *
 * @author Bai
 * @version 1.5.1
 * @since 2025.08.21
 */
@Repository
public class TornSettingOcDAO extends ServiceImpl<TornSettingOcMapper, TornSettingOcDO> {
    /**
     * 批量插入缺失的OC目录。
     *
     * @param list 待插入的OC目录列表，不能为空列表
     * @return 实际插入行数
     */
    public int insertMissingBatch(List<TornSettingOcDO> list) {
        return baseMapper.insertMissingBatch(list);
    }
}