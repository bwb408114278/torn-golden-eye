package pn.torn.goldeneye.msg.strategy.faction.crime.rotation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.PnManageMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.torn.TornOcUtils;

import java.util.Comparator;
import java.util.List;

/**
 * OC咸鱼队设置抽象实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.25
 */
@Component
@RequiredArgsConstructor
public class SetOcSkipRotationStrategyImpl extends PnManageMsgStrategy {
    private final TornFactionOcSlotDAO slotDao;
    private final TornFactionOcDAO ocDao;
    private final TornUserDAO userDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_SKIP_TEAM;
    }

    @Override
    public String getCommandDescription() {
        return "设置OC咸鱼队，例g#" + BotCommands.OC_SKIP_TEAM + "#用户ID";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!NumberUtils.isInt(msg)) {
            return super.sendErrorFormatMsg();
        }

        long userId = Long.parseLong(msg);
        TornUserDO user = userDao.getById(userId);
        if (user == null || !user.getFactionId().equals(TornConstants.FACTION_PN_ID)) {
            return super.buildTextMsg("金蝶不认识TA哦");
        }

        List<TornFactionOcSlotDO> slotList = slotDao.lambdaQuery().eq(TornFactionOcSlotDO::getUserId, userId).list();
        TornFactionOcSlotDO slot = slotList.stream()
                .max(Comparator.comparing(TornFactionOcSlotDO::getJoinTime))
                .orElse(null);
        if (slot == null) {
            return super.buildTextMsg("未找到TA的OC参加记录");
        }

        TornFactionOcDO oc = ocDao.getById(slot.getOcId());
        if (TornOcStatusEnum.COMPLETED.getCode().equals(oc.getStatus())) {
            return super.buildTextMsg("这个OC已经完成啦");
        }

        if (!TornOcUtils.isEnableRotation(oc)) {
            return super.buildTextMsg(oc.getRank() + "级" + oc.getName() + "没必要设置咸鱼队吧");
        }

        ocDao.lambdaUpdate()
                .set(TornFactionOcDO::getHasSkipRotation, true)
                .eq(TornFactionOcDO::getId, slot.getOcId())
                .update();
        return super.buildTextMsg(user.getNickname() + "所在的队伍已设置为咸鱼队");
    }
}