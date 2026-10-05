package pn.torn.goldeneye.napcat.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.service.faction.oc.image.rate.OcRateTableData;
import pn.torn.goldeneye.torn.service.faction.oc.image.rate.OcRateTableDocumentAssembler;
import pn.torn.goldeneye.torn.service.faction.oc.image.rate.OcRateTierResolver;
import pn.torn.goldeneye.utils.image.render.TableImageRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OC成功率查询实现类
 *
 * @author Bai
 * @version 1.9.0
 * @since 2025.08.20
 */
@Component
@RequiredArgsConstructor
public class OcRateQueryStrategyImpl extends SmthMsgStrategy {
    private final TornFactionOcUserDAO ocUserDao;
    private final TornSettingOcManager settingOcManager;
    private final TornSettingOcSlotManager settingOcSlotManager;
    private final TornSettingFactionOcManager settingFactionOcManager;
    private final OcRateTableDocumentAssembler rateTableDocumentAssembler;
    private final TableImageRenderer tableImageRenderer;

    @Override
    public String getCommand() {
        return BotCommands.OC_PASS_RATE;
    }

    @Override
    public String getCommandDescription() {
        return "获取OC成功率，例g#" + BotCommands.OC_PASS_RATE + "(#用户ID)";
    }

    @Override
    public boolean supportsAtUserTarget() {
        return true;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornUserDO user = super.getTornUser(sender, msg);
        List<TornFactionOcUserDO> ocUserList = ocUserDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getUserId, user.getId())
                .orderByDesc(TornFactionOcUserDO::getRank)
                .orderByAsc(TornFactionOcUserDO::getOcName)
                .orderByAsc(TornFactionOcUserDO::getPosition)
                .list();
        if (ocUserList.isEmpty()) {
            return super.buildTextMsg("暂未查询到记录的OC成功率");
        }

        return super.buildImageMsg(buildPassRateMsg(user, ocUserList));
    }

    /**
     * 构建成功率图片Base64
     */
    private String buildPassRateMsg(TornUserDO user, List<TornFactionOcUserDO> ocUserList) {
        List<TornSettingOcDO> ocList = filterOcList(user, ocUserList, settingOcManager.getList());
        ocList.sort(Comparator
                .comparing(TornSettingOcDO::getRank, Comparator.reverseOrder())
                .thenComparing(TornSettingOcDO::getOcName));

        OcRateTableData data = new OcRateTableData(user, ocList, settingOcSlotManager.getList(),
                ocUserList, getFactionSlots(user.getFactionId()));
        return tableImageRenderer.render(rateTableDocumentAssembler.assemble(data));
    }

    /**
     * 按7级OC岗位要求过滤展示范围
     */
    private List<TornSettingOcDO> filterOcList(TornUserDO user, List<TornFactionOcUserDO> ocUserList,
                                               List<TornSettingOcDO> ocList) {
        boolean qualifiedForRankSeven = isQualifiedForRankSeven(user, ocUserList);
        int minRank = qualifiedForRankSeven ? 7 : 1;
        int maxRank = qualifiedForRankSeven ? 10 : 7;
        return new ArrayList<>(ocList.stream()
                .filter(oc -> oc.getRank() >= minRank && oc.getRank() <= maxRank)
                .toList());
    }

    /**
     * 是否满足帮派任意7级OC岗位要求
     */
    private boolean isQualifiedForRankSeven(TornUserDO user, List<TornFactionOcUserDO> ocUserList) {
        List<TornSettingFactionOcSlotDO> factionSlots = getFactionSlots(user.getFactionId());
        List<TornSettingOcSlotDO> rankSevenSlotList = settingOcSlotManager.getList().stream()
                .filter(slot -> slot.getRank().equals(7))
                .toList();
        for (TornSettingOcSlotDO slot : rankSevenSlotList) {
            int requiredPassRate = OcRateTierResolver.requiredPassRate(slot, factionSlots);
            boolean matched = ocUserList.stream()
                    .filter(ocUser -> ocUser.getRank().equals(slot.getRank()))
                    .filter(ocUser -> ocUser.getOcName().equals(slot.getOcName()))
                    .filter(ocUser -> ocUser.getPosition().equals(slot.getSlotShortCode()))
                    .anyMatch(ocUser -> ocUser.getPassRate() >= requiredPassRate);
            if (matched) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取指定帮派的岗位要求覆盖列表
     */
    private List<TornSettingFactionOcSlotDO> getFactionSlots(long factionId) {
        return settingFactionOcManager.getSlotList().stream()
                .filter(s -> s.getFactionId().equals(factionId))
                .toList();
    }
}
