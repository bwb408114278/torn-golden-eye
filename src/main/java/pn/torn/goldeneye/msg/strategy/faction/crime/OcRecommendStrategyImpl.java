package pn.torn.goldeneye.msg.strategy.faction.crime;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.ImageQqMsg;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.oc.msg.TornFactionOcMsgTableManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcSlotDictBO;
import pn.torn.goldeneye.torn.service.faction.oc.TornFactionOcService;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcReassignRecommendService;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcRecommendService;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.math.BigDecimal;
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
public class OcRecommendStrategyImpl extends PnMsgStrategy {
    private final TornFactionOcService ocService;
    private final TornOcRecommendService recommendService;
    private final TornOcReassignRecommendService reassignRecommendService;
    private final TornSettingOcSlotManager settingOcSlotManager;
    private final TornFactionOcMsgTableManager tableManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final TornFactionOcUserDAO ocUserDao;

    public OcRecommendStrategyImpl(TornFactionOcService ocService,
                                   @Qualifier("tornOcRecommendService") TornOcRecommendService recommendService,
                                   @Qualifier("tornOcReassignRecommendService") TornOcReassignRecommendService reassignRecommendService,
                                   TornSettingOcSlotManager settingOcSlotManager, TornFactionOcMsgTableManager tableManager,
                                   TornFactionOcDAO ocDao, TornFactionOcSlotDAO slotDao,
                                   TornFactionOcUserDAO ocUserDao) {
        this.ocService = ocService;
        this.recommendService = recommendService;
        this.reassignRecommendService = reassignRecommendService;
        this.settingOcSlotManager = settingOcSlotManager;
        this.tableManager = tableManager;
        this.ocDao = ocDao;
        this.slotDao = slotDao;
        this.ocUserDao = ocUserDao;
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
        TornUserDO user = super.getTornUser(sender, "");
        ocService.refreshOc(1, user.getFactionId());

        OcSlotDictBO ocSlotDict = getJoinedOc(user);
        if (ocSlotDict != null && BigDecimal.ZERO.compareTo(ocSlotDict.getSlot().getProgress()) < 0) {
            return super.buildTextMsg(user.getNickname() + ", 跑了进度换队要被打的");
        }

        List<TornFactionOcUserDO> userOcData = ocUserDao.queryByUserId(user.getId());
        if (CollectionUtils.isEmpty(userOcData)) {
            return super.buildTextMsg(user.getNickname() + ", 未查询到记录的OC成功率数据, 推荐失败");
        }

        boolean isReassign = checkIsReassignRecommended(user, userOcData);
        List<OcRecommendationVO> result = isReassign ?
                reassignRecommendService.recommendOcForUser(user, 3, ocSlotDict, userOcData) :
                recommendService.recommendOcForUser(user, 3, ocSlotDict, userOcData);
        if (CollectionUtils.isEmpty(result)) {
            return super.buildTextMsg(user.getNickname() + ", 暂时没有合适加入的OC");
        }

        TornFactionOcSlotDO joinedSlot = ocSlotDict == null ? null : ocSlotDict.getSlot();
        if (joinedSlot != null &&
                result.getFirst().getOcId().equals(joinedSlot.getOcId()) &&
                result.getFirst().getRecommendedPosition().equals(joinedSlot.getPosition())) {
            return super.buildTextMsg(user.getNickname() + ", 当前加入岗位已是最佳选择");
        }

        return buildRecommendTable(user, result);
    }

    /**
     * 检测是否大锅饭推荐
     * return true为推荐大锅饭
     */
    private boolean checkIsReassignRecommended(TornUserDO user, List<TornFactionOcUserDO> userOcData) {
        if (!user.getFactionId().equals(TornConstants.FACTION_PN_ID)) {
            return false;
        }

        List<TornSettingOcSlotDO> reassignSlotList = settingOcSlotManager.getList().stream()
                .filter(s -> TornConstants.ROTATION_OC_NAME.contains(s.getOcName()))
                .toList();

        boolean isMatch = false;
        for (TornSettingOcSlotDO setting : reassignSlotList) {
            TornFactionOcUserDO matchData = userOcData.stream()
                    .filter(u -> u.getOcName().equals(setting.getOcName()))
                    .filter(u -> u.getPosition().equals(setting.getSlotShortCode()))
                    .filter(u -> u.getPassRate().compareTo(setting.getPassRate()) > -1)
                    .findAny().orElse(null);
            if (matchData != null) {
                isMatch = true;
                break;
            }
        }

        return isMatch;
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

        List<TornFactionOcDO> ocList = ocDao.queryListByIdList(user.getFactionId(),
                result.stream().map(OcRecommendationVO::getOcId).toList());
        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(ocList);
        Multimap<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap = LinkedListMultimap.create();
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
            reasonList.offer(recommend.getRank() + "级" +
                    "   " + recommend.getOcName() +
                    "   岗位: " + recommend.getRecommendedPosition() +
                    "   评分: " + recommend.getRecommendScore() +
                    "   推荐理由: " + recommend.getReason());
        }

        String tableData = TableImageUtils.renderTableToBase64(tableManager.buildOcTable(title, ocMap, reasonList));
        return super.buildImageMsg(tableData);
    }
}