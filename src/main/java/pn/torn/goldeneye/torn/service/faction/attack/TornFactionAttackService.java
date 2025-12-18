package pn.torn.goldeneye.torn.service.faction.attack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.constants.torn.enums.TornFactionNewsTypeEnum;
import pn.torn.goldeneye.repository.dao.faction.attack.TornFactionAttackDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionAttackDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsDTO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsListVO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsVO;
import pn.torn.goldeneye.torn.model.user.TornUserDTO;
import pn.torn.goldeneye.torn.model.user.TornUserProfileVO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;
import pn.torn.goldeneye.torn.service.data.TornAttackLogService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 帮派攻击记录逻辑类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornFactionAttackService {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornAttackLogService attackLogService;
    private final TornFactionAttackDAO attackDao;
    private static final Pattern USER_ID_PATTERN = Pattern.compile("XID=(\\d+)");
    private static final Pattern RESPECT_PATTERN = Pattern.compile("\\(([+-]?\\d+\\.\\d+)\\)");
    private static final Pattern ATTACK_LOG_ID_PATTERN = Pattern.compile("attackLog&ID=([a-f0-9]+)");

    /**
     * 爬取攻击记录
     */
    public void spiderAttackData(TornSettingFactionDO faction, LocalDateTime from, LocalDateTime to) {
        int limit = 100;
        TornFactionNewsDTO param;
        LocalDateTime queryTo = to;
        List<TornFactionAttackDO> newsList;
        Map<Long, TornUserProfileVO> userMap = new HashMap<>();

        do {
            param = new TornFactionNewsDTO(TornFactionNewsTypeEnum.ATTACK, from, queryTo, limit);
            TornFactionNewsListVO resp = tornApi.sendRequest(faction.getId(), param, TornFactionNewsListVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getNews())) {
                break;
            }

            newsList = parseNewsList(resp, userMap);
            if (!CollectionUtils.isEmpty(newsList)) {
                attackDao.saveBatch(newsList);
                for (TornFactionAttackDO attack : newsList) {
                    virtualThreadExecutor.execute(() -> attackLogService.saveAttackLog(attack.getAttackLogId()));
                }
            }

            queryTo = DateTimeUtils.convertToDateTime(resp.getNews().getLast().getTimestamp());
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (newsList.size() >= limit);
    }


    /**
     * 解析新闻列表为攻击记录
     */
    public List<TornFactionAttackDO> parseNewsList(TornFactionNewsListVO resp, Map<Long, TornUserProfileVO> userFactionMap) {
        if (resp == null || resp.getNews() == null) {
            return new ArrayList<>();
        }

        List<String> idList = resp.getNews().stream().map(TornFactionNewsVO::getId).toList();
        List<TornFactionAttackDO> recordList = attackDao.lambdaQuery().in(TornFactionAttackDO::getId, idList).list();

        List<TornFactionAttackDO> attackList = new ArrayList<>();
        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        for (TornFactionNewsVO news : resp.getNews()) {
            futureList.add(CompletableFuture.runAsync(() -> {
                        try {
                            TornFactionAttackDO attack = parseNews(news, userFactionMap);
                            boolean isExists = recordList.stream()
                                    .anyMatch(r -> r.getId().equals(news.getId()));
                            if (attack != null && !isExists) {
                                attackList.add(attack);
                            }
                        } catch (Exception e) {
                            log.error("解析新闻失败, id: {}, text: {}", news.getId(), news.getText(), e);
                        }
                    },
                    virtualThreadExecutor));
        }

        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
        return attackList;
    }

    /**
     * 解析单条新闻为攻击记录
     */
    public TornFactionAttackDO parseNews(TornFactionNewsVO news, Map<Long, TornUserProfileVO> userMap) {
        if (news == null || news.getText() == null) {
            return null;
        }

        TornFactionAttackDO attack = new TornFactionAttackDO();
        attack.setId(news.getId());
        attack.setAttackTime(DateTimeUtils.convertToDateTime(news.getTimestamp()));

        // 解析HTML文本
        Document doc = Jsoup.parse(news.getText());
        Elements links = doc.select("a[href*=profiles.php]");
        String plainText = doc.text();

        // 判断是否为匿名攻击
        boolean isAnonymous = news.getText().startsWith("Someone");
        int defendIndex = 0;
        if (isAnonymous) {
            attack.setAttackUserId(0L);
            attack.setAttackFactionId(0L);
            attack.setAttackUserNickname("Someone");
        } else {
            Element attackLink = links.getFirst();
            attack.setAttackUserId(extractUserId(attackLink.attr("href")));
            attack.setAttackFactionId(extractFactionId(attack.getAttackUserId(), userMap));
            attack.setAttackUserNickname(attackLink.text());
            defendIndex = 1;
        }

        Element defendLink = links.get(defendIndex);
        attack.setDefendUserId(extractUserId(defendLink.attr("href")));
        attack.setDefendFactionId(extractFactionId(attack.getDefendUserId(), userMap));
        attack.setDefendUserNickname(defendLink.text());

        TornUserProfileVO profile = userMap.get(attack.getDefendUserId());
        attack.setDefendUserOnlineStatus(profile == null ? "" : profile.getStatus().getColor());

        attack.setAttackResult(parseAttackResult(plainText));
        attack.setRespectChange(extractRespectChange(plainText));
        attack.setAttackLogId(extractAttackLogId(news.getText()));

        return attack;
    }

    /**
     * 从URL中提取用户ID
     */
    private long extractUserId(String href) {
        Matcher matcher = USER_ID_PATTERN.matcher(href);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }

        return 0L;
    }

    /**
     * 提取帮派ID
     */
    private Long extractFactionId(long userId, Map<Long, TornUserProfileVO> userMap) {
        if (userId == 0L) {
            return null;
        }

        TornUserProfileVO user = userMap.get(userId);
        if (user != null) {
            return user.getFactionId();
        }

        TornUserVO resp = tornApi.sendRequest(new TornUserDTO(userId), TornUserVO.class);
        if (resp != null && resp.getProfile() != null) {
            user = resp.getProfile();
            userMap.put(userId, user);
            return user.getFactionId();
        }

        return null;
    }

    /**
     * 解析攻击结果
     */
    private String parseAttackResult(String plainText) {
        if (plainText.contains("attacked")) {
            return "leave";
        } else if (plainText.contains("mugged")) {
            return "mug";
        } else if (plainText.contains("hospitalized")) {
            return "hospital";
        } else if (plainText.contains("but lost")) {
            return "lost";
        } else if (plainText.contains("and escaped")) {
            return "escape";
        } else if (plainText.contains("and stalemated")) {
            return "stalemate";
        }

        return plainText;
    }

    /**
     * 提取面子变化
     */
    private BigDecimal extractRespectChange(String plainText) {
        Matcher matcher = RESPECT_PATTERN.matcher(plainText);
        if (matcher.find()) {
            try {
                String respectStr = matcher.group(1);
                return new BigDecimal(respectStr);
            } catch (NumberFormatException e) {
                log.warn("解析面子失败: {}", plainText, e);
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 提供攻击日志ID
     */
    private String extractAttackLogId(String htmlText) {
        Matcher matcher = ATTACK_LOG_ID_PATTERN.matcher(htmlText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
}