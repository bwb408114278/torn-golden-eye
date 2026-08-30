package pn.torn.goldeneye.napcat.strategy.base;

import jakarta.annotation.Resource;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessage;
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.List;

/**
 * 基础消息策略
 *
 * @author Bai
 * @version 1.5.2
 * @since 2025.07.24
 */
public abstract class BaseMsgStrategy {
    /**
     * 消息携带 at 目标但当前策略不支持用户目标查询时的稳定提示文案。
     */
    public static final String AT_UNSUPPORTED_MSG = "该指令不支持@用户查询";

    @Resource
    protected TornUserManager userManager;

    /**
     * 获取指令
     *
     * @return 指令
     */
    public abstract String getCommand();

    /**
     * 获取指令描述
     *
     * @return 指令描述
     */
    public abstract String getCommandDescription();

    /**
     * 处理消息
     *
     * @param sender 消息发送人
     * @param msg    消息
     * @return 需要发送的消息，为空则为不发送
     */
    public abstract List<? extends QqMsgParam<?>> handle(QqRecMsgSender sender, String msg);

    /**
     * 当前策略是否支持通过 QQ at 指定用户查询目标。
     *
     * <p>默认返回 {@code false} 表示不支持 at；只有命令参数语义为“单个 Torn 用户目标”且
     * 通过 {@link #getTornUser(QqRecMsgSender, String)} 解析目标参数的策略才覆写为 {@code true}。
     * 非 at 用户目标策略收到 at 时由消息处理器统一返回 {@link #AT_UNSUPPORTED_MSG}，
     * 不会静默丢弃 at。</p>
     *
     * @return true 表示支持 at 用户目标
     */
    public boolean supportsAtUserTarget() {
        return false;
    }

    /**
     * 发送文本消息
     *
     * @param msg 消息内容
     */
    public List<TextQqMsg> buildTextMsg(String msg) {
        return List.of(new TextQqMsg(msg));
    }

    /**
     * 发送图片消息
     *
     * @param base64 图片Base64
     */
    protected List<ImageQqMsg> buildImageMsg(String base64) {
        return List.of(ImageQqMsg.fromBase64(base64));
    }

    /**
     * 发送错误格式的消息
     */
    protected List<TextQqMsg> sendErrorFormatMsg() {
        return buildTextMsg("参数有误");
    }

    /**
     * 根据消息和发送人获取用户
     */
    protected TornUserDO getTornUser(QqRecMsgSender sender, String msg) {
        TornUserDO user = getTornUserWithoutException(sender, msg);
        if (user == null) {
            throw new BizException("金蝶不认识你哦");
        }

        return user;
    }

    /**
     * 根据消息和发送人获取用户, 不抛出异常
     *
     * <p>用户目标优先级：内部 at 标记优先解析为 QQ 用户；无 at 时保留原有规则，
     * 有文本参数按 Torn userId 查询，无参数按发送者 QQ 查询。</p>
     */
    protected TornUserDO getTornUserWithoutException(QqRecMsgSender sender, String msg) {
        if (hasAtMarker(msg)) {
            return getTornUserByAtMarker(msg);
        }

        TornUserDO user;
        if (StringUtils.hasText(msg)) {
            String[] msgArray = msg.split("#");
            if (msgArray.length < 1 || !NumberUtils.isLong(msgArray[0])) {
                throw new BizException("参数有误");
            }

            long userId = Long.parseLong(msgArray[0]);
            user = userManager.getUserById(userId);
        } else {
            user = userManager.getUserByQq(sender.getUserId());
        }

        return user;
    }

    /**
     * 判断策略参数或其拆分出的目标片段是否包含解析层生成的内部 at 标记。
     *
     * <p>供“模式#目标”类策略在拆分业务模式后探测目标片段是否携带 at 标记；
     * 标记结构与 QQ 合法性校验仍统一由 {@link #getTornUser(QqRecMsgSender, String)} 完成。</p>
     *
     * @param msg 策略参数或拆分出的目标片段
     * @return true 表示包含内部 at 标记
     */
    protected boolean hasAtMarker(String msg) {
        return msg != null && (msg.contains(QqCommandMessage.AT_MARKER_PREFIX)
                || msg.contains(QqCommandMessage.INVALID_AT_MARKER));
    }

    /**
     * 根据内部 at 标记查询 QQ 绑定的 Torn 用户。
     *
     * @param msg 策略参数
     * @return Torn 用户；未绑定时返回 {@code null}
     */
    private TornUserDO getTornUserByAtMarker(String msg) {
        if (msg.contains(QqCommandMessage.INVALID_AT_MARKER)) {
            throw new BizException("参数有误");
        }

        int prefixIndex = msg.indexOf(QqCommandMessage.AT_MARKER_PREFIX);
        if (prefixIndex < 0) {
            throw new BizException("参数有误");
        }

        int valueStart = prefixIndex + QqCommandMessage.AT_MARKER_PREFIX.length();
        int suffixIndex = msg.indexOf(QqCommandMessage.AT_MARKER_SUFFIX, valueStart);
        if (suffixIndex < 0) {
            throw new BizException("参数有误");
        }
        if (msg.indexOf(QqCommandMessage.AT_MARKER_PREFIX, valueStart) >= 0) {
            throw new BizException("参数有误");
        }

        String before = msg.substring(0, prefixIndex);
        String after = msg.substring(suffixIndex + QqCommandMessage.AT_MARKER_SUFFIX.length());
        if (!before.isBlank() || !after.isBlank()) {
            throw new BizException("参数有误");
        }

        String qqText = msg.substring(valueStart, suffixIndex);
        if (!NumberUtils.isLong(qqText)) {
            throw new BizException("参数有误");
        }
        long qq = Long.parseLong(qqText);
        if (qq <= 0L) {
            throw new BizException("参数有误");
        }

        return userManager.getUserByQq(qq);
    }

    /**
     * 根据消息和发送人获取帮派ID
     */
    protected long getTornFactionId(String msg) {
        long factionId;
        if (StringUtils.hasText(msg)) {
            String[] msgArray = msg.split("#");
            if (msgArray.length < 1 || !NumberUtils.isLong(msgArray[0])) {
                throw new BizException("参数有误");
            }

            factionId = Long.parseLong(msgArray[0]);
        } else {
            factionId = 0L;
        }

        return factionId;
    }

    /**
     * 根据发送人获取帮派ID；未绑定帮派时返回 0
     */
    protected long getTornFactionIdBySender(QqRecMsgSender sender) {
        TornUserDO user = getTornUser(sender, "");
        Long factionId = user == null ? null : user.getFactionId();
        return factionId == null ? 0L : factionId;
    }
}