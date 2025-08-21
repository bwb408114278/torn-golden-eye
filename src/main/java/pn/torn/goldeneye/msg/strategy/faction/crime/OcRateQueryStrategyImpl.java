package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;
import pn.torn.goldeneye.utils.torn.TornUserUtils;

import java.awt.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * OC成功率查询实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.20
 */
@Component
@RequiredArgsConstructor
public class OcRateQueryStrategyImpl extends PnMsgStrategy {
    private final TornApiKeyDAO keyDao;
    private final TornUserDAO userDao;
    private final TornFactionOcUserDAO ocUserDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_PASS_RATE;
    }

    @Override
    public String getCommandDescription() {
        return "获取OC成功率，例g#" + BotCommands.OC_PASS_RATE + "(#用户ID)";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        long userId;
        if (StringUtils.hasText(msg)) {
            String[] msgArray = msg.split("#");
            if (msgArray.length < 1 || !NumberUtils.isLong(msgArray[0])) {
                return super.sendErrorFormatMsg();
            }

            userId = Long.parseLong(msgArray[0]);
        } else {
            userId = TornUserUtils.getUserIdFromSender(sender);
        }

        if (userId == 0L) {
            return super.buildTextMsg("金蝶不认识TA哦");
        }

        TornUserDO user = userDao.getById(userId);
        if (user == null) {
            return super.buildTextMsg("未找到该用户");
        }

        TornApiKeyDO key = keyDao.lambdaQuery()
                .eq(TornApiKeyDO::getUserId, userId)
                .eq(TornApiKeyDO::getUseDate, LocalDate.now())
                .one();
        if (key == null) {
            return super.buildTextMsg("这个人还没有绑定Key哦");
        }

        List<TornFactionOcUserDO> ocUserList = ocUserDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getUserId, userId)
                .orderByDesc(TornFactionOcUserDO::getRank)
                .orderByAsc(TornFactionOcUserDO::getOcName)
                .orderByAsc(TornFactionOcUserDO::getPosition)
                .list();
        if (ocUserList.isEmpty()) {
            return super.buildTextMsg("暂未查询到记录的OC成功率");
        }

        return super.buildImageMsg(buildPassRateMsg(userId, ocUserList));
    }

    /**
     * 构建OC成员列表消息
     *
     * @return 消息内容
     */
    private String buildPassRateMsg(long userId, List<TornFactionOcUserDO> ocUserList) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of(userId + "的OC成功率", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 4);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "OC名称", "岗位", "成功率"));
        tableConfig.setSubTitle(1, 4);

        for (TornFactionOcUserDO ocUser : ocUserList) {
            tableData.add(List.of(
                    ocUser.getRank().toString(),
                    ocUser.getOcName(),
                    ocUser.getPosition(),
                    ocUser.getPassRate().toString()));
        }
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}