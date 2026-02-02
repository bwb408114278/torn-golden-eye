package pn.torn.goldeneye.napcat.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcRefreshManager;
import pn.torn.goldeneye.torn.manager.faction.crime.msg.TornFactionOcMsgManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendTableBO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcSlotDictBO;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcRecommendService;

import java.util.List;

/**
 * OC推荐策略实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.07
 */
@Component
@RequiredArgsConstructor
public class OcRecommendStrategyImpl extends PnMsgStrategy {
    private final TornFactionOcRefreshManager ocRefreshManager;
    private final TornOcRecommendService recommendService;
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;

    @Override
    public List<Long> getCustomGroupId() {
        return List.of(projectProperty.getGroupId(),
                BotConstants.GROUP_HP_ID,
                BotConstants.GROUP_CCRC_ID,
                BotConstants.GROUP_SH_ID);
    }

    @Override
    public String getCommand() {
        return BotCommands.OC_RECOMMEND;
    }

    @Override
    public String getCommandDescription() {
        return "选择金蝶Team, 选择成功";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornUserDO user = super.getTornUser(sender, msg);
        ocRefreshManager.refreshOc(1, user.getFactionId());

        OcSlotDictBO joinedOc = getJoinedOc(user);
        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);
        if (CollectionUtils.isEmpty(result)) {
            return super.buildTextMsg(user.getNickname() + ", 暂时没有合适加入的OC");
        }

        TornFactionOcSlotDO joinedSlot = joinedOc == null ? null : joinedOc.getSlot();
        if (joinedSlot != null &&
                result.getFirst().getOcId().equals(joinedSlot.getOcId()) &&
                result.getFirst().getRecommendedPosition().equals(joinedSlot.getPosition())) {
            return super.buildTextMsg(user.getNickname() + ", 当前加入岗位已是最佳选择");
        }

        return buildRecommendTable(user, result);
    }

    /**
     * 获取用户已参加的OC
     */
    private OcSlotDictBO getJoinedOc(TornUserDO user) {
        List<TornFactionOcDO> ocList = ocDao.queryExecutingOc(user.getFactionId());
        List<Long> ocIdList = ocList.stream().map(TornFactionOcDO::getId).toList();
        TornFactionOcSlotDO slot = slotDao.lambdaQuery()
                .eq(TornFactionOcSlotDO::getUserId, user.getId())
                .in(TornFactionOcSlotDO::getOcId, ocIdList)
                .one();
        if (slot == null) {
            return null;
        }

        TornFactionOcDO oc = ocList.stream()
                .filter(o -> o.getId().equals(slot.getOcId()))
                .findAny().orElseThrow(() -> new BizException("OC数据异常"));
        return new OcSlotDictBO(oc, slot);
    }

    /**
     * 构建建议表格
     */
    private List<ImageQqMsg> buildRecommendTable(TornUserDO user, List<OcRecommendationVO> result) {
        String title = user.getNickname() + ", 推荐加入以下队伍";
        String table = msgManager.buildRecommendTable(title, user.getFactionId(),
                result.stream().map(r -> new OcRecommendTableBO(null, r)).toList());
        return super.buildImageMsg(table);
    }
}