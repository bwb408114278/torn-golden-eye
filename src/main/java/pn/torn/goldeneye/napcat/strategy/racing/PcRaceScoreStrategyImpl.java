package pn.torn.goldeneye.napcat.strategy.racing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceScoreBO;
import pn.torn.goldeneye.torn.service.racing.image.PcRaceTextAssembler;
import pn.torn.goldeneye.torn.service.racing.query.PcRaceQueryService;

import java.util.List;

/**
 * PC赛车个人成绩查询策略。
 *
 * <p>公开指令，不需要管理权限；目标用户复用基类的用户解析，支持at、数字ID与默认本人。</p>
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Component
@RequiredArgsConstructor
public class PcRaceScoreStrategyImpl extends SmthMsgStrategy {
    private final PcRaceQueryService queryService;
    private final PcRaceTextAssembler textAssembler;

    @Override
    public String getCommand() {
        return BotCommands.PC_RACE_SCORE;
    }

    @Override
    public String getCommandDescription() {
        return "查询SMTHPC个人成绩，格式g#" + BotCommands.PC_RACE_SCORE + "#用户ID或@某人";
    }

    @Override
    public boolean supportsAtUserTarget() {
        return true;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornUserDO user = getTornUser(sender, msg);
        PcRaceScoreBO score = queryService.buildScore(user.getId());
        if (score == null || score.items().isEmpty()) {
            return buildTextMsg("暂无赛车成绩记录");
        }

        return buildTextMsg(textAssembler.assembleScore(score));
    }
}
