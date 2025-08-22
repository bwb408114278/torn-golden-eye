package pn.torn.goldeneye.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.*;

/**
 * 字符串模糊匹配工具
 *
 * @author zmpress
 * @version 0.1.0
 * @since 2025.08.18
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.NONE)
public class StringFuzzyMatchUtils {
    /**
     * 模糊匹配字符串
     *
     * @param input    用户输入的字符串
     * @param wordsMap key是需要匹配的命令，value的每个命令对应的别名
     * @return 匹配到的命令，可能有多个，也可能为空
     */
    public static List<String> fuzzyMatching(String input, Map<String, Set<String>> wordsMap) {
        // 模糊匹配命令本身
        List<TextScore> keywordResult = fuzzyMatchingScore(input, wordsMap.keySet());
        if (keywordResult.size() == 1) {
            return List.of(keywordResult.get(0).text());
        }

        // 模糊匹配Alias
        List<TextScore> aliasResult = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : wordsMap.entrySet()) {
            List<TextScore> aliasMatchList = fuzzyMatchingScore(input, entry.getValue());
            if (aliasMatchList.size() == 1 && aliasMatchList.get(0).score() == 100D) {
                return List.of(entry.getKey());
            } else if (!aliasMatchList.isEmpty()) {
                aliasResult.add(new TextScore(entry.getKey(), aliasMatchList.get(0).score()));
            }
        }

        aliasResult.sort(Comparator.comparing(TextScore::score, Comparator.reverseOrder()));
        return aliasResult.stream().map(TextScore::text).toList();
    }

    private static List<TextScore> fuzzyMatchingScore(String input, Set<String> words) {
        if (StringUtils.isBlank(input) || CollectionUtils.isEmpty(words)) {
            return List.of();
        }

        String finalInput = input.replace(" ", "");
        List<TextScore> resultList = new ArrayList<>();
        for (String word : words) {
            if (StringUtils.equalsAnyIgnoreCase(word, finalInput)) {
                resultList.clear();
                resultList.add(new TextScore(word, 100D));
                return resultList;
            } else if (StringUtils.startsWithIgnoreCase(word, finalInput)) {
                resultList.add(new TextScore(word, 80D));
            } else if (StringUtils.containsAnyIgnoreCase(word, finalInput)) {
                resultList.add(new TextScore(word, 60D));
            }
        }

        resultList.sort(Comparator.comparing(TextScore::score, Comparator.reverseOrder()));
        return resultList;
    }

    /**
     * 文本相似度评分
     *
     * @param text  文本
     * @param score 评分
     */
    private record TextScore(String text, double score) {
    }
}