package pn.torn.goldeneye.napcat.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * OC推荐策略实现类
 *
 * @author Bai
 * @version 1.5.1
 * @since 2025.11.07
 */
@Component
@RequiredArgsConstructor
public class OcRecommendStrategyImpl extends SmthMsgStrategy {
    private final TornFactionOcRefreshManager ocRefreshManager;
    private final TornOcRecommendService recommendService;
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final ProjectProperty projectProperty;

    @Override
    public String getCommand() {
        return BotCommands.OC_RECOMMEND;
    }

    @Override
    public String getCommandDescription() {
        return "选择金蝶Team, 选择成功";
    }

    @Override
    public boolean supportsAtUserTarget() {
        return true;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornUserDO user = super.getTornUser(sender, msg);
        if (BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            ocRefreshManager.refreshOc(1, user.getFactionId());
        }

        OcSlotDictBO joinedOc = getJoinedOc(user);
        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);
        TornOcRecommendService.CurrentOcStatus currentStatus = recommendService.queryCurrentOcStatus(user, joinedOc);
        if (currentStatus.disabled()) {
            return buildDisabledWithRecommendMessage(user, result);
        }
        if (currentStatus.passRateInsufficient()) {
            return buildInsufficientWithRecommendMessage(user, currentStatus, joinedOc, result);
        }
        if (CollectionUtils.isEmpty(result)) {
            if (currentStatus.joined() && currentStatus.currentScore() != null) {
                return super.buildTextMsg(user.getNickname() + ", 当前加入岗位已是最佳选择");
            }
            return super.buildTextMsg(user.getNickname() + ", 暂时没有合适加入的OC");
        }

        return buildRecommendTable(user, result);
    }

    /**
     * 构建禁用提示，对齐巡检定稿文案，禁用语义下不再附带成功率信息。
     *
     * @param user 用户
     * @return 禁用提示文本
     */
    private String buildDisabledMessage(TornUserDO user) {
        return user.getNickname() + ", 你加入了禁用的OC, 需要更换其他OC";
    }

    /**
     * 禁用当前OC时组合禁用提示与正常推荐结果；推荐为空时明确提示未找到正常OC。
     *
     * @param user   用户
     * @param result OC推荐结果
     * @return 禁用提示在前、正常推荐文本/链接/图片在后的消息列表
     */
    private List<QqMsgParam<?>> buildDisabledWithRecommendMessage(TornUserDO user,
                                                                  List<OcRecommendationVO> result) {
        List<QqMsgParam<?>> messageList = new ArrayList<>();
        messageList.add(new TextQqMsg(buildDisabledMessage(user)));
        if (CollectionUtils.isEmpty(result)) {
            messageList.add(new TextQqMsg(user.getNickname() + ", 暂未找到可加入的正常OC"));
            return messageList;
        }
        messageList.addAll(buildRecommendTable(user, result));
        return messageList;
    }

    /**
     * 成功率不足时组合状态提示与推荐结果，推荐范围由服务层限定（有进度时仅本队）；
     * 推荐为空时有进度用户提示找OC指挥官决定是否换队，无进度用户提示未找到达标岗位。
     *
     * @param user          用户
     * @param currentStatus 当前OC状态
     * @param joinedOc      当前占用的OC岗位
     * @param result        OC推荐结果
     * @return 状态提示在前、推荐文本/链接/图片在后的消息列表
     */
    private List<QqMsgParam<?>> buildInsufficientWithRecommendMessage(TornUserDO user,
                                                                      TornOcRecommendService.CurrentOcStatus currentStatus,
                                                                      OcSlotDictBO joinedOc,
                                                                      List<OcRecommendationVO> result) {
        List<QqMsgParam<?>> messageList = new ArrayList<>();
        messageList.add(new TextQqMsg(buildInsufficientMessage(user, currentStatus, joinedOc)));
        if (CollectionUtils.isEmpty(result)) {
            messageList.add(new TextQqMsg(buildNoRecommendMessage(user, joinedOc)));
            return messageList;
        }
        messageList.addAll(buildRecommendTable(user, result));
        return messageList;
    }

    /**
     * 构建成功率不足状态提示，文案对齐巡检定稿格式。
     *
     * @param user     用户
     * @param status   当前OC状态
     * @param joinedOc 当前占用的OC岗位
     * @return 状态提示文本
     */
    private String buildInsufficientMessage(TornUserDO user, TornOcRecommendService.CurrentOcStatus status,
                                            OcSlotDictBO joinedOc) {
        return user.getNickname() + ", 当前岗位" + joinedOc.getSlot().getPosition()
                + "成功率: " + status.actualPassRate() + ", 帮派要求: " + status.requiredPassRate();
    }

    /**
     * 构建成功率不足且无推荐时的兜底提示：有进度用户换队需OC指挥官决策，无进度用户仅提示无达标岗位。
     *
     * @param user     用户
     * @param joinedOc 当前占用的OC岗位
     * @return 兜底提示文本
     */
    private String buildNoRecommendMessage(TornUserDO user, OcSlotDictBO joinedOc) {
        if (hasProgress(joinedOc)) {
            return user.getNickname() + ", 本队暂无适合岗位, 请找OC指挥官决定是否换队";
        }
        return user.getNickname() + ", 暂未找到成功率达标的岗位";
    }

    /**
     * 判断当前岗位是否已有进度（成功率不足的分支下joinedOc必非空）。
     *
     * @param joinedOc 当前占用的OC岗位
     * @return true表示已有进度
     */
    private boolean hasProgress(OcSlotDictBO joinedOc) {
        return BigDecimal.ZERO.compareTo(joinedOc.getSlot().getProgress()) < 0;
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
    private List<QqMsgParam<?>> buildRecommendTable(TornUserDO user, List<OcRecommendationVO> result) {
        String title = user.getNickname() + ", 推荐加入以下队伍";
        String table = msgManager.buildRecommendTable(title, user.getFactionId(),
                result.stream().map(r -> new OcRecommendTableBO(null, r)).toList());
        List<QqMsgParam<?>> msgList = new ArrayList<>();
        msgList.add(new TextQqMsg(buildRecommendText(result)));
        msgList.addAll(super.buildImageMsg(table));
        return msgList;
    }

    /**
     * 构建建议文本
     */
    private String buildRecommendText(List<OcRecommendationVO> result) {
        StringBuilder builder = new StringBuilder("推荐加入：");
        for (OcRecommendationVO recommendation : result) {
            builder.append("\n")
                    .append(recommendation.getOcName())
                    .append(" - ")
                    .append(recommendation.getRecommendedPosition())
                    .append("\n")
                    .append("https://www.torn.com/factions.php?step=your&type=12#/tab=crimes&crimeId=")
                    .append(recommendation.getOcId());
        }
        return builder.toString();
    }
}
