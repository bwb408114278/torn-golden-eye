package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornOcPositionEnum;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcUserManager;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class OcMemberStrategyImpl extends PnMsgStrategy {
    private final TornFactionOcUserManager userManager;
    private final TornUserDAO userDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_FREE;
    }

    @Override
    public String getCommandDescription() {
        return "获取空闲成员，例g#" + BotCommands.OC_FREE + "#8";
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(long groupId, String msg) {
        String[] msgArray = msg.split("#");
        if (msgArray.length < 1 || !NumberUtils.isInt(msgArray[0])) {
            return super.sendErrorFormatMsg();
        }

        int rank = Integer.parseInt(msgArray[0]);
        String position = msgArray.length > 1 ? msgArray[1] : null;
        TornOcPositionEnum positionEnum = TornOcPositionEnum.codeOf(position, rank);
        if (StringUtils.hasText(position) && positionEnum == null) {
            return super.buildTextMsg("没有这个岗位");
        }

        List<TornFactionOcUserDO> ocUserList = userManager.findFreeUser(
                positionEnum == null ? null : positionEnum.getCode(), rank);
        if (CollectionUtils.isEmpty(ocUserList)) {
            return super.buildTextMsg("暂时没有可以加入OC的成员");
        }

        Set<Long> userIdSet = ocUserList.stream().map(TornFactionOcUserDO::getUserId).collect(Collectors.toSet());
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(userIdSet);

        ocUserList.sort(
                Comparator.comparing(TornFactionOcUserDO::getOcName)
                        .thenComparing(TornFactionOcUserDO::getPosition)
                        .thenComparing(TornFactionOcUserDO::getPassRate, Comparator.reverseOrder()));

        return super.buildImageMsg(buildFreeMemberMsg(ocUserList, userMap));
    }

    /**
     * 构建OC成员列表消息
     *
     * @return 消息内容
     */
    private String buildFreeMemberMsg(List<TornFactionOcUserDO> ocUserList, Map<Long, TornUserDO> userMap) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of("可加入OC成员", "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 6);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        tableData.add(List.of("Rank", "ID ", "Name", "OC名称", "岗位", "成功率"));
        tableConfig.setSubTitle(1, 6);

        for (int i = 0; i < ocUserList.size(); i++) {
            TornFactionOcUserDO ocUser = ocUserList.get(i);
            TornUserDO user = userMap.get(ocUser.getUserId());

            tableData.add(List.of(
                    String.valueOf(i + 1),
                    ocUser.getUserId().toString(),
                    user.getNickname(),
                    ocUser.getOcName(),
                    ocUser.getPosition(),
                    ocUser.getPassRate().toString()));
        }
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}