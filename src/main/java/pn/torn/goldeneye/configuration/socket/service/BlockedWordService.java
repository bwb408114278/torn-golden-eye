package pn.torn.goldeneye.configuration.socket.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.constants.bot.enums.GroupMsgTypeEnum;
import pn.torn.goldeneye.napcat.receive.BaseQqRec;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsg;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgDetail;
import pn.torn.goldeneye.napcat.send.DeleteMsgReqParam;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgSocketBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.model.setting.SysBlockWordDO;
import pn.torn.goldeneye.torn.manager.setting.SysBlockWordManager;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.model.faction.TornFactionBO;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 屏蔽词逻辑层
 *
 * @author Bai
 * @version 1.1.3
 * @since 2026.05.20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlockedWordService {
    private final SysSettingManager settingManager;
    private final SysBlockWordManager blockWordManager;
    private final Bot bot;
    private final BotReplyService botReplyService;

    /**
     * 仅处理 http/https 超链接
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[\\w\\-.~:/?#\\[\\]@!$&'()*+,;=%]+)", Pattern.CASE_INSENSITIVE);

    /**
     * 处理屏蔽词消息
     *
     * @return true 表示已经处理，不需要继续后续流程
     */
    public boolean handleBlockedWords(QqRecMsg msg, TornFactionBO faction) {
        if (isBlockSender(msg)) {
            return false;
        }

        List<QqMsgParam<?>> replaceList = replaceBlockMsg(msg, faction);
        if (CollectionUtils.isEmpty(replaceList)) {
            return false;
        }

        ResponseEntity<BaseQqRec> resp = bot.sendRequest(new DeleteMsgReqParam(msg.getMessageId()), BaseQqRec.class);
        BaseQqRec body = resp.getBody();
        if (body == null || "failed".equals(body.getStatus())) {
            return false;
        }

        GroupMsgSocketBuilder builder = new GroupMsgSocketBuilder().setGroupId(msg.getGroupId());
        String senderCard = msg.getSender() != null ? msg.getSender().getCard() : "";
        builder.addMsg(new TextQqMsg(senderCard + " 刚才说:\n"));
        replaceList.forEach(builder::addMsg);

        botReplyService.replyGroup(faction, builder.build());
        return true;
    }

    /**
     * 是否为屏蔽的发送者
     */
    private boolean isBlockSender(QqRecMsg msg) {
        List<Long> botId = settingManager.getBotId();
        return botId.contains(msg.getSender().getUserId());
    }

    /**
     * 替换屏蔽词
     */
    public List<QqMsgParam<?>> replaceBlockMsg(QqRecMsg msg, TornFactionBO faction) {
        if (!shouldHandleBlockWord(msg, faction)) {
            return List.of();
        }

        Map<String, SysBlockWordDO> wordMap = blockWordManager.getWordMap();
        if (CollectionUtils.isEmpty(wordMap)) {
            return List.of();
        }

        List<QqMsgParam<?>> resultList = new ArrayList<>();
        AtomicBoolean hasReplacement = new AtomicBoolean(false);

        for (QqRecMsgDetail detail : msg.getMessage()) {
            if (!GroupMsgTypeEnum.TEXT.getCode().equals(detail.getType())) {
                resultList.add(detail.convertToParam());
                continue;
            }

            String originalText = detail.getData().getText();
            String replacedText = replaceTextIgnoringUrl(originalText, wordMap, hasReplacement);
            resultList.add(new TextQqMsg(replacedText));
        }

        return hasReplacement.get() ? resultList : List.of();
    }

    /**
     * 是否需要处理屏蔽词
     */
    private boolean shouldHandleBlockWord(QqRecMsg msg, TornFactionBO faction) {
        return faction != null
                && !Objects.equals(faction.getGroupId(), 0L)
                && Boolean.TRUE.equals(faction.getIsAdmin())
                && !faction.getAllAdminQq().contains(msg.getSender().getUserId());
    }

    /**
     * 跳过 URL 对普通文本做替换
     */
    private String replaceTextIgnoringUrl(String originalText, Map<String, SysBlockWordDO> wordMap,
                                          AtomicBoolean hasReplacement) {
        if (!StringUtils.hasText(originalText)) {
            return originalText;
        }

        Matcher matcher = URL_PATTERN.matcher(originalText);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            // 处理链接前的普通文本
            String normalText = originalText.substring(lastEnd, matcher.start());
            sb.append(replaceBlockWordsInPlainText(normalText, wordMap, hasReplacement));

            // 链接保留
            sb.append(matcher.group());

            lastEnd = matcher.end();
        }

        // 最后一个链接后的尾部文本
        if (lastEnd < originalText.length()) {
            String tailText = originalText.substring(lastEnd);
            sb.append(replaceBlockWordsInPlainText(tailText, wordMap, hasReplacement));
        }

        return sb.toString();
    }

    /**
     * 普通文本屏蔽词替换
     */
    private String replaceBlockWordsInPlainText(String originalText, Map<String, SysBlockWordDO> wordMap,
                                                AtomicBoolean hasReplacement) {
        String safeText = originalText == null ? "" : originalText;
        String replacedText = safeText;
        String normalizedText = normalize(safeText);

        for (Map.Entry<String, SysBlockWordDO> entry : wordMap.entrySet()) {
            String key = entry.getKey();
            SysBlockWordDO blockWord = entry.getValue();

            if (!needReplace(normalizedText, key, blockWord)) {
                continue;
            }

            String regex = "(?i)" + buildFlexiblePattern(key);
            String replacement = Matcher.quoteReplacement(
                    blockWord.getReplaceWord() == null ? "" : blockWord.getReplaceWord());
            String newText = replacedText.replaceAll(regex, replacement);

            if (!Objects.equals(newText, replacedText)) {
                replacedText = newText;
                hasReplacement.set(true);
            }
        }

        return replacedText;
    }

    /**
     * 是否需要替换
     */
    private boolean needReplace(String normalizedText, String key, SysBlockWordDO blockWord) {
        String normalizedKey = normalize(key);
        if (!normalizedText.contains(normalizedKey)) {
            return false;
        }

        if (!StringUtils.hasText(blockWord.getWhiteList())) {
            return true;
        }

        return Arrays.stream(blockWord.getWhiteList().split(","))
                .map(this::normalize)
                .noneMatch(normalizedText::contains);
    }

    /**
     * 文本归一化：去空格 + 小写
     */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(" ", "").toLowerCase();
    }

    /**
     * 构建允许中间有空白的匹配正则
     * abc -> a\s*b\s*c
     */
    private String buildFlexiblePattern(String word) {
        String cleaned = word == null ? "" : word.replace(" ", "");
        StringBuilder regex = new StringBuilder();

        for (int i = 0; i < cleaned.length(); i++) {
            if (i > 0) {
                regex.append("\\s*");
            }
            regex.append(Pattern.quote(String.valueOf(cleaned.charAt(i))));
        }

        return regex.toString();
    }
}