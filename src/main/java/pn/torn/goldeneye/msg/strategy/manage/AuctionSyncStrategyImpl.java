package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.torn.service.data.TornAuctionService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步拍卖记录实现类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.14
 */
@Component
@RequiredArgsConstructor
public class AuctionSyncStrategyImpl extends BaseGroupMsgStrategy {
    private final TornAuctionService auctionService;

    @Override
    public boolean isNeedSa() {
        return true;
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return null;
    }

    @Override
    public String getCommand() {
        return BotCommands.AUCTION_SYNC;
    }

    @Override
    public String getCommandDescription() {
        return "强制刷新拍卖记录，慎用（格式不告诉你）";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        String[] msgArray = msg.split("#");
        if (msgArray.length < 2) {
            return super.sendErrorFormatMsg();
        }

        LocalDateTime from = DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[0]));
        LocalDateTime to = DateTimeUtils.convertToDateTime(Long.parseLong(msgArray[1]));
        if (from.isAfter(to)) {
            return super.sendErrorFormatMsg();
        }

        auctionService.spiderAuctionData(from, to, false);
        return super.buildTextMsg("同步" +
                DateTimeUtils.convertToString(from) +
                "到" +
                DateTimeUtils.convertToString(to) +
                "的拍卖记录完成");
    }
}