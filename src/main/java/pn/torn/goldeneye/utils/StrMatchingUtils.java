package pn.torn.goldeneye.utils;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornOcPositionAliasEnum;
import pn.torn.goldeneye.constants.torn.enums.TornOcPositionEnum;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Data
@Component
public class StrMatchingUtils {

    public static Map<String, Set<String>> botCommandsMap = new HashMap<>();

    public static Map<String, Set<String>> tornOcPositionMap = new HashMap<>();

    private static boolean currentAliasExactMatch = false;

    @PostConstruct
    public void init() throws Exception {
        // 获取BotCommands类的所有String字段（包括 private）
        Field[] fields = BotCommands.class.getDeclaredFields();
        for (Field field : fields) {
            field.setAccessible(true); // 允许访问 private 字段
            Object value = field.get(null); // 取出字段的值
            if (value instanceof String) {
                botCommandsMap.put((String) value, null);
            }
        }

        // 检查TornOcPositionAliasEnum别名配置是否重复
        Set<String> set = new HashSet<>();
        for (TornOcPositionAliasEnum tornOcPositionAliasEnum : TornOcPositionAliasEnum.values()) {
            String[] aliases = tornOcPositionAliasEnum.getAliases();
            if (aliases != null) {
                for (String alias : aliases) {
                    boolean add = set.add(alias);
                    if (!add) {
                        log.error("TornOcPositionAliasEnum不要配置完全一样的别名【{}】,模糊匹配会有问题", alias);
                    }
                }
            }
        }

        // 获取TornOcPositionEnum所有的职位名称及其对应别名
        Set<String> tornOcPositionSet = Arrays.stream(TornOcPositionEnum.values())
                .map(TornOcPositionEnum::getCode).collect(Collectors.toSet());
        for (String tornOcPosition : tornOcPositionSet) {
            TornOcPositionAliasEnum tornOcPositionAliasEnum = TornOcPositionAliasEnum.keyOf(tornOcPosition);
            if (null != tornOcPositionAliasEnum) {
                String[] aliases = tornOcPositionAliasEnum.getAliases();
                if (null != aliases && aliases.length > 0) {
                    tornOcPositionMap.put(tornOcPosition, new HashSet<>(Arrays.asList(aliases)));
                }
            } else {
                tornOcPositionMap.put(tornOcPosition, null);
            }
        }

    }


    public static void main(String[] args) throws Exception {
        new StrMatchingUtils().init();
        System.out.println(fuzzyMatching("oc", botCommandsMap));
        System.out.println(fuzzyMatching("查询", botCommandsMap));
        System.out.println(fuzzyMatching("小红", botCommandsMap));
        System.out.println(fuzzyMatching("小", botCommandsMap));
        System.out.println(fuzzyMatching("偷车", tornOcPositionMap));
        System.out.println(fuzzyMatching("司机", tornOcPositionMap));
        System.out.println(fuzzyMatching("贼", tornOcPositionMap));
    }


    /**
     * @param input    用户输入的字符串
     * @param wordsMap key是需要匹配的命令，value的每个命令对应的别名
     * @return 匹配到的命令，可能有多个，也可能为空
     */
    public static List<String> fuzzyMatching(String input, Map<String, Set<String>> wordsMap) {

        // 模糊匹配命令本身
        Set<String> keywordResult = fuzzyMatching(input, wordsMap.keySet());

        if (keywordResult.size() == 1) {
            return new ArrayList<>(keywordResult);
        }
        currentAliasExactMatch = false;
        // 别名alias
        Set<String> keywordAliasResult = new HashSet<>();
        for (Map.Entry<String, Set<String>> stringSetEntry : wordsMap.entrySet()) {
            Set<String> aliasMachingSet = fuzzyMatching(input, stringSetEntry.getValue());
            if (currentAliasExactMatch) {
                currentAliasExactMatch = false;
                keywordAliasResult = new HashSet<>();
                keywordAliasResult.add(stringSetEntry.getKey());
                break;
            }
            if (!CollectionUtils.isEmpty(aliasMachingSet)) {
                keywordAliasResult.add(stringSetEntry.getKey());
            }
        }

        if (keywordAliasResult.size() == 1) {
            return new ArrayList<>(keywordAliasResult);
        }

        keywordResult.addAll(keywordAliasResult);
        log.info("当前输入单词【{}】匹配到多个或没有结果【{}】", input, keywordResult);
        return new ArrayList<>(keywordResult);
    }

    private static Set<String> fuzzyMatching(String input, Set<String> words) {

        if (StringUtils.isBlank(input) || CollectionUtils.isEmpty(words)) {
            return new HashSet<>();
        }

        String finalInput = input.replace(" ", "");

        // 1.匹配equalsAnyIgnoreCase的字符串
        Set<String> equalsCollect = words.stream()
                .filter(word -> StringUtils.equalsAnyIgnoreCase(word, finalInput))
                .collect(Collectors.toSet());
        if (!CollectionUtils.isEmpty(equalsCollect)) {
            currentAliasExactMatch = true;
            return equalsCollect;
        }

        // 2.最左匹配 startsWithIgnoreCase
        Set<String> startsCollect = words.stream()
                .filter(word -> StringUtils.startsWithIgnoreCase(word, finalInput))
                .collect(Collectors.toSet());
        if (!CollectionUtils.isEmpty(startsCollect)) {
            return startsCollect;
        }

        // 3.包含关系 containsAnyIgnoreCase
        Set<String> containsCollect = words.stream()
                .filter(word -> StringUtils.containsAnyIgnoreCase(word, finalInput))
                .collect(Collectors.toSet());
        if (!CollectionUtils.isEmpty(containsCollect)) {
            return containsCollect;
        }

        return new HashSet<>();
    }


}
