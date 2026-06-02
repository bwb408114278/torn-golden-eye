package pn.torn.goldeneye.napcat.strategy.vip;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.torn.manager.torn.stocks.StockFeatureBuildService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * VIP股票特征值初始化策略实现类
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.06.01
 */
@Component
@RequiredArgsConstructor
public class VipStocksBuildStrategyImpl extends BaseGroupMsgStrategy {
    private final StockFeatureBuildService featureBuildService;

    @Override
    public String getCommand() {
        return BotCommands.STOCK_FEATURE_SYNC;
    }

    @Override
    public String getCommandDescription() {
        return "补齐未计算的股票特征值";
    }

    @Override
    public boolean isNeedSa() {
        return true;
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return null;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        String[] msgArray = msg.split("#");
        LocalDateTime startTime = DateTimeUtils.convertToDateTime(msgArray[0]);
        LocalDateTime endTime = DateTimeUtils.convertToDateTime(msgArray[1]);
        featureBuildService.buildBetween(startTime, endTime);
        return super.buildTextMsg("同步股票特征完成");
    }
}