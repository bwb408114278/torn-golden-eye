package pn.torn.goldeneye.napcat.strategy.faction.attack;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionRwDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RW基础策略
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.05.21
 */
public abstract class BaseRwStrategy extends SmthMsgStrategy {
    @Resource
    private TornFactionRwDAO rwDao;
    @Resource
    private ProjectProperty projectProperty;

    @Override
    public List<Long> getCustomGroupId() {
        return List.of(projectProperty.getGroupId(),
                BotConstants.GROUP_CCRC_ID,
                BotConstants.GROUP_SH_ID,
                BotConstants.GROUP_HP_ID);
    }

    /**
     * 获取最近一场Rw
     */
    protected TornFactionRwDO getCurrentRw(QqRecMsgSender sender, String msg) {
        if (StringUtils.hasText(msg) && !NumberUtils.isLong(msg)) {
            throw new BizException("请输入RW ID, 没有ID时取最近一场");
        }

        long factionId = super.getTornFactionIdBySender(sender);
        long rwId = NumberUtils.isLong(msg) ? Long.parseLong(msg) : 0L;

        Page<TornFactionRwDO> rwList = rwDao.lambdaQuery()
                .eq(TornFactionRwDO::getFactionId, factionId)
                .eq(rwId > 0L, TornFactionRwDO::getId, rwId)
                .le(rwId == 0L, TornFactionRwDO::getStartTime, LocalDateTime.now())
                .orderByDesc(TornFactionRwDO::getStartTime)
                .page(new Page<>(1, 1));
        if (CollectionUtils.isEmpty(rwList.getRecords())) {
            throw new BizException("未查询到RW记录");
        }

        return rwList.getRecords().getFirst();
    }
}