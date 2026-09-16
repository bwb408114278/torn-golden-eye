package pn.torn.goldeneye.napcat.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.torn.service.faction.attack.contribution.RwScoreBackfillService;

import java.util.List;

/**
 * RW真赛比分回填策略
 *
 * <p>仅超管可执行的一次性历史数据回填指令，可重复执行，
 * 生产验证完成后随同 {@link BotCommands#RW_SCORE_BACKFILL} 常量一并删除。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@Component
@RequiredArgsConstructor
public class RwScoreBackfillStrategyImpl extends BaseGroupMsgStrategy {
    private final RwScoreBackfillService backfillService;

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
        return BotCommands.RW_SCORE_BACKFILL;
    }

    @Override
    public String getCommandDescription() {
        return "一次性回填历史真赛比分与名次";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        return super.buildTextMsg(backfillService.backfillAll());
    }
}
