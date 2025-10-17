package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;

import java.util.List;

/**
 * 更新缓存策略实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.09.17
 */
@Component
@RequiredArgsConstructor
public class RefreshCacheStrategyImpl extends BaseGroupMsgStrategy {
    private final List<DataCacheManager> cacheManagerList;

    @Override
    public boolean isNeedSa() {
        return true;
    }

    @Override
    public String getCommand() {
        return BotCommands.REFRESH_CACHE;
    }

    @Override
    public String getCommandDescription() {
        return "刷新缓存";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        cacheManagerList.forEach(DataCacheManager::refreshCache);
        return super.buildTextMsg("缓存刷新成功");
    }
}