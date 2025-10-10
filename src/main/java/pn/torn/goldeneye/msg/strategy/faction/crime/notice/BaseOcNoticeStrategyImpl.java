package pn.torn.goldeneye.msg.strategy.faction.crime.notice;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcNoticeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcNoticeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.torn.TornUserUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * OC通知设置抽象实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.20
 */
@RequiredArgsConstructor
public abstract class BaseOcNoticeStrategyImpl extends PnMsgStrategy {
    @Resource
    private TornFactionOcNoticeDAO noticeDao;
    @Resource
    private TornFactionOcUserDAO ocUserDao;
    @Resource
    private TornApiKeyDAO apiKeyDao;

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        if (!NumberUtils.isInt(msg)) {
            return super.sendErrorFormatMsg();
        }

        int rank = Integer.parseInt(msg);
        if (!TornConstants.ROTATION_OC_RANK.contains(rank)) {
            String enableRank = String.join("/",
                    TornConstants.ROTATION_OC_RANK.stream().map(Object::toString).toList());
            return super.buildTextMsg("只能设置" + enableRank + "级OC");
        }

        long userId = findUserId(sender);
        if (userId == 0L) {
            return super.buildTextMsg("金蝶不认识你哦");
        }

        if (!checkPassRate(userId, rank)) {
            return super.buildTextMsg("成功率真过60了吗，小心土豆把你当土豆削");
        }

        TornFactionOcNoticeDO old = noticeDao.lambdaQuery()
                .eq(TornFactionOcNoticeDO::getUserId, userId)
                .eq(TornFactionOcNoticeDO::getRank, rank)
                .one();
        if (old != null && old.getHasNotice().equals(hasNotice())) {
            return super.buildTextMsg("设置已存在");
        }

        if (old == null) {
            TornFactionOcNoticeDO notice = new TornFactionOcNoticeDO();
            notice.setUserId(userId);
            notice.setRank(rank);
            notice.setHasNotice(hasNotice());
            noticeDao.save(notice);
        } else {
            noticeDao.lambdaUpdate()
                    .set(TornFactionOcNoticeDO::getHasNotice, hasNotice())
                    .eq(TornFactionOcNoticeDO::getId, old.getId())
                    .update();
        }

        return super.buildTextMsg(buildSuccessMsg(userId, rank));
    }

    /**
     * 是否通知
     *
     * @return true为是
     */
    protected abstract boolean hasNotice();

    /**
     * 构建设置成功消息
     *
     * @param userId 用户ID
     * @param rank   等级
     * @return 发送到群里的消息
     */
    protected abstract String buildSuccessMsg(long userId, int rank);

    /**
     * 获取用户Id
     */
    private long findUserId(QqRecMsgSender sender) {
        long userId = TornUserUtils.getUserIdFromSender(sender);
        if (userId == 0L) {
            TornApiKeyDO apiKey = apiKeyDao.lambdaQuery()
                    .eq(TornApiKeyDO::getQqId, sender.getUserId())
                    .eq(TornApiKeyDO::getUseDate, LocalDate.now())
                    .one();
            userId = apiKey == null ? 0L : apiKey.getUserId();
        }

        return userId;
    }

    /**
     * 校验成功率
     */
    private boolean checkPassRate(long userId, int rank) {
        if (!hasNotice()) {
            return true;
        }

        List<TornFactionOcUserDO> ocUserList = ocUserDao.queryListByUserAndRank(userId, rank);
        return ocUserList.stream().anyMatch(s -> s.getPassRate() >= 60);
    }
}