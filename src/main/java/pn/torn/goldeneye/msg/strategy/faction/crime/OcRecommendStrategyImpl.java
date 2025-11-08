package pn.torn.goldeneye.msg.strategy.faction.crime;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.oc.msg.TornFactionOcMsgTableManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.service.faction.oc.TornFactionOcService;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcRecommendService;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.util.ArrayList;
import java.util.LinkedList;
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
    private final TornFactionOcService ocService;
    private final TornOcRecommendService recommendService;
    private final TornFactionOcMsgTableManager tableManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;

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
        // ocService.refreshOc(1, TornConstants.FACTION_PN_ID);

        TornUserDO user = super.getTornUser(sender, "");
        if (user.getFactionId() == null || !user.getFactionId().equals(TornConstants.FACTION_PN_ID)) {
            return super.buildTextMsg("该功能内测中, 稍后开放");
        }

        if (isUserInOc(user.getId())) {
            return super.buildTextMsg(user.getNickname() + ", 跑了进度换队要被打的");
        }

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user.getId(), 3);
        if (CollectionUtils.isEmpty(result)) {
            return super.buildTextMsg(user.getNickname() + ", 暂时没有合适加入的OC");
        }

        String title = user.getNickname() + ", 推荐加入以下队伍";


        List<TornFactionOcDO> ocList = ocDao.queryListByIdList(TornConstants.FACTION_PN_ID,
                result.stream().map(OcRecommendationVO::getOcId).toList());
        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(ocList);
        Multimap<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap = ArrayListMultimap.create();
        LinkedList<String> reasonList = new LinkedList<>();

        for (OcRecommendationVO recommend : result) {
            TornFactionOcDO oc = ocList.stream()
                    .filter(o -> o.getId().equals(recommend.getOcId()))
                    .findAny().orElse(null);
            if (oc == null) {
                continue;
            }

            List<TornFactionOcSlotDO> currentSlotList = new ArrayList<>(slotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId())).toList());
            ocMap.put(oc, currentSlotList);
            reasonList.add(recommend.getRank() + "级" +
                    "   " + recommend.getOcName() +
                    "   岗位: " + recommend.getRecommendedPosition() +
                    "   评分: " + recommend.getRecommendScore() +
                    "   推荐理由: " + recommend.getReason());
        }

        String tableData = TableImageUtils.renderTableToBase64(tableManager.buildOcTable(title, ocMap, reasonList));
        return super.buildImageMsg(tableData);
    }

    /**
     * 检查用户是否已在某个OC中
     */
    private boolean isUserInOc(long userId) {
        // 查找所有未完成的OC
        List<Long> ocIdList = ocDao.queryExecutingOc(TornConstants.FACTION_PN_ID).stream()
                .map(TornFactionOcDO::getId).toList();
        long count = slotDao.lambdaQuery()
                .eq(TornFactionOcSlotDO::getUserId, userId)
                .in(TornFactionOcSlotDO::getOcId, ocIdList)
                .count();
        return count > 0;
    }
}