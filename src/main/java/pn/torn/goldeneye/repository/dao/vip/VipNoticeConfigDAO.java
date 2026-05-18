package pn.torn.goldeneye.repository.dao.vip;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.vip.VipNoticeConfigMapper;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeConfigDO;

/**
 * VIP提醒设置持久层类
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.11
 */
@Repository
public class VipNoticeConfigDAO extends ServiceImpl<VipNoticeConfigMapper, VipNoticeConfigDO> {
    /**
     * 获取一个配置，如果没有就创建他
     */
    public VipNoticeConfigDO getOrCreate(TornUserDO user) {
        VipNoticeConfigDO config = lambdaQuery().eq(VipNoticeConfigDO::getUserId, user.getId()).one();
        if (config == null) {
            config = new VipNoticeConfigDO(user.getId(), user.getQqId());
            save(config);
        }

        return config;
    }
}