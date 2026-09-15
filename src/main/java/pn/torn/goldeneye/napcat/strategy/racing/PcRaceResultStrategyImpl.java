package pn.torn.goldeneye.napcat.strategy.racing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceResultBO;
import pn.torn.goldeneye.torn.service.racing.image.PcRaceDocumentAssembler;
import pn.torn.goldeneye.torn.service.racing.image.PcRaceTextAssembler;
import pn.torn.goldeneye.torn.service.racing.query.PcRaceQueryService;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.image.render.TableImageRenderer;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * PC赛车榜单查询策略。
 *
 * <p>公开指令，不需要管理权限，也不触发任何抓取行为。</p>
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Component
@RequiredArgsConstructor
public class PcRaceResultStrategyImpl extends SmthMsgStrategy {
    private final PcRaceQueryService queryService;
    private final PcRaceDocumentAssembler documentAssembler;
    private final PcRaceTextAssembler textAssembler;
    private final TableImageRenderer imageRenderer;

    @Override
    public String getCommand() {
        return BotCommands.PC_RACE_RESULT;
    }

    @Override
    public String getCommandDescription() {
        return "查询SMTHPC赛车榜单，格式g#" + BotCommands.PC_RACE_RESULT + "#日期或赛事ID";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        PcRaceResultBO result;
        if (!StringUtils.hasText(msg)) {
            result = queryService.buildResultByBusinessDate(DateTimeUtils.getTornLocalDate().minusDays(1));
        } else {
            LocalDate businessDate = parseBusinessDate(msg);
            if (businessDate != null) {
                result = queryService.buildResultByBusinessDate(businessDate);
            } else if (NumberUtils.isLong(msg)) {
                result = queryService.buildResultByRaceId(Long.parseLong(msg));
            } else {
                return buildTextMsg("参数有误");
            }
        }

        if (result == null) {
            return buildTextMsg("未查询到SMTHPC赛事数据，可能尚未抓取");
        }

        String image = imageRenderer.render(documentAssembler.assemble(result));
        return List.<QqMsgParam<?>>of(ImageQqMsg.fromBase64(image),
                new TextQqMsg(textAssembler.assembleSummary(result)));
    }

    /**
     * 解析业务日期参数，非日期格式时返回null。
     *
     * @param msg 指令参数
     * @return 业务日期；不可解析时返回null
     */
    private LocalDate parseBusinessDate(String msg) {
        try {
            return DateTimeUtils.convertToDate(msg);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
