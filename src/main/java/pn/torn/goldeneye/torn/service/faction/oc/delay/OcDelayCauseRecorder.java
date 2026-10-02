package pn.torn.goldeneye.torn.service.faction.oc.delay;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.oc.delay.OcDelayCauseEntry;
import pn.torn.goldeneye.torn.model.faction.oc.delay.OcDelayReasonEnum;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OC延误归因的编解码与段结算。
 * <p>
 * 只处理 {@link OcDelayCauseEntry} 的读写与累计，不访问数据库、不渲染文案；
 * 编码格式为 {@code userId|原因|道具ID|当前段起始分钟|已累计分钟}，条目之间用 {@code ;} 分隔。
 *
 * @author Bai
 * @version 1.6.7
 * @since 2026.10.02
 */
@Slf4j
@Component
public class OcDelayCauseRecorder {
    /**
     * 归因条目分隔符。
     */
    private static final String ENTRY_SEPARATOR = ";";
    /**
     * 归因字段分隔符。
     */
    private static final String FIELD_SEPARATOR = "|";
    /**
     * 归因字段分隔符的正则形式，供字符串拆分使用。
     */
    private static final String FIELD_SEPARATOR_REGEX = "\\|";
    /**
     * 单个条目的字段数量。
     */
    private static final int FIELD_COUNT = 5;
    /**
     * 无补充道具时落库的占位道具ID。
     */
    private static final int NO_ITEM_ID = 0;

    /**
     * 解析归因编码。
     *
     * @param delayCause 数据库中的编码；空值返回空列表
     * @return 归因条目；无法解析的条目被跳过
     */
    public List<OcDelayCauseEntry> decode(String delayCause) {
        if (delayCause == null || delayCause.isBlank()) {
            return List.of();
        }

        Map<Long, OcDelayCauseEntry> entryMap = new LinkedHashMap<>();
        for (String text : delayCause.split(ENTRY_SEPARATOR)) {
            OcDelayCauseEntry entry = parseEntry(text);
            if (entry != null) {
                entryMap.putIfAbsent(entry.userId(), entry);
            }
        }

        return new ArrayList<>(entryMap.values());
    }

    /**
     * 合并一次采样结果。
     * <p>
     * 未出现在采样中的成员视为本轮未阻塞；成员的原因与补充道具保持首次观测值，
     * 后续采样只开启或闭合阻塞段。
     *
     * @param delayCause 现有编码
     * @param samples    本次采样命中的成员及其原因
     * @param sampleTime 采样时刻
     * @return 合并后的编码；与入参相同时返回入参原值，调用方据此跳过写库
     */
    public String merge(String delayCause, Map<Long, OcDelayReasonResolver.OcDelayReason> samples,
                        LocalDateTime sampleTime) {
        long minute = toMinuteBucket(sampleTime);
        List<OcDelayCauseEntry> entries = decode(delayCause);
        Set<Long> knownUserIdSet = new LinkedHashSet<>();
        List<OcDelayCauseEntry> mergedEntries = new ArrayList<>(entries.size() + samples.size());
        boolean isChanged = false;

        for (OcDelayCauseEntry entry : entries) {
            knownUserIdSet.add(entry.userId());
            boolean isBlocked = samples.containsKey(entry.userId());
            OcDelayCauseEntry merged = isBlocked ? blockEntry(entry, minute) : entry.closeSegment(minute);
            isChanged = isChanged || !merged.equals(entry);
            mergedEntries.add(merged);
        }

        for (Map.Entry<Long, OcDelayReasonResolver.OcDelayReason> sample : samples.entrySet()) {
            if (knownUserIdSet.contains(sample.getKey())) {
                continue;
            }

            OcDelayReasonResolver.OcDelayReason reason = sample.getValue();
            mergedEntries.add(new OcDelayCauseEntry(sample.getKey(), reason.reason(),
                    reason.itemId(), minute, 0));
            isChanged = true;
        }

        return isChanged ? encode(mergedEntries) : delayCause;
    }

    /**
     * 用实际完成时间封闭全部未闭合段。
     *
     * @param delayCause   现有编码
     * @param executedTime OC实际执行时间
     * @return 结算后的编码；没有任何未闭合段时返回入参原值，调用方据此跳过写库
     */
    public String settle(String delayCause, LocalDateTime executedTime) {
        long minute = toMinuteBucket(executedTime);
        List<OcDelayCauseEntry> entries = decode(delayCause);
        List<OcDelayCauseEntry> settledEntries = new ArrayList<>(entries.size());
        boolean isChanged = false;

        for (OcDelayCauseEntry entry : entries) {
            OcDelayCauseEntry settled = entry.closeSegment(minute);
            isChanged = isChanged || !settled.equals(entry);
            settledEntries.add(settled);
        }

        return isChanged ? encode(settledEntries) : delayCause;
    }

    /**
     * 把时刻截断到分钟后换算为epoch分钟桶，作为延误时长的唯一时间口径。
     * <p>
     * LocalDateTime不带时区，固定按UTC偏移换算；归因只使用同一口径下的分钟差，
     * 结果与运行环境的默认时区无关。
     *
     * @param time 时刻
     * @return epoch分钟；时刻为空时返回0
     */
    public static long toMinuteBucket(LocalDateTime time) {
        if (time == null) {
            return OcDelayCauseEntry.NO_OPEN_SEGMENT;
        }

        return time.truncatedTo(ChronoUnit.MINUTES).toEpochSecond(ZoneOffset.UTC) / 60;
    }

    /**
     * 采样命中时的段处理：无开放段则开启新段，已有开放段则保持当前段不变。
     *
     * @param entry  现有条目
     * @param minute 采样时刻所在的分钟桶
     * @return 处理后的条目
     */
    private OcDelayCauseEntry blockEntry(OcDelayCauseEntry entry, long minute) {
        return entry.hasOpenSegment() ? entry : entry.startSegment(minute);
    }

    /**
     * 解析单个归因条目，字段缺失、数量不符或格式非法时跳过。
     *
     * @param text 单个条目的编码文本
     * @return 归因条目；无法解析时返回null
     */
    private OcDelayCauseEntry parseEntry(String text) {
        String[] fields = text.split(FIELD_SEPARATOR_REGEX, -1);
        if (fields.length != FIELD_COUNT) {
            log.warn("OC延误归因条目字段数异常，已跳过, text={}", text);
            return null;
        }

        OcDelayReasonEnum reason = OcDelayReasonEnum.codeOf(fields[1]);
        if (reason == null) {
            log.warn("OC延误归因原因编码未知，已跳过, text={}", text);
            return null;
        }

        try {
            int itemId = Integer.parseInt(fields[2]);
            return new OcDelayCauseEntry(Long.parseLong(fields[0]), reason,
                    itemId == NO_ITEM_ID ? null : itemId,
                    Long.parseLong(fields[3]), Integer.parseInt(fields[4]));
        } catch (NumberFormatException e) {
            log.warn("OC延误归因条目解析失败，已跳过, text={}", text);
            return null;
        }
    }

    /**
     * 编码全部归因条目。
     *
     * @param entries 归因条目
     * @return 归因编码；无条目时为空字符串
     */
    private String encode(List<OcDelayCauseEntry> entries) {
        return entries.stream()
                .map(this::encodeEntry)
                .collect(Collectors.joining(ENTRY_SEPARATOR));
    }

    /**
     * 编码单个归因条目。
     *
     * @param entry 归因条目
     * @return 形如 {@code userId|原因|道具ID|段起始分钟|已累计分钟} 的编码
     */
    private String encodeEntry(OcDelayCauseEntry entry) {
        return entry.userId() + FIELD_SEPARATOR + entry.reason().getCode() + FIELD_SEPARATOR
                + (entry.itemId() == null ? NO_ITEM_ID : entry.itemId()) + FIELD_SEPARATOR
                + entry.segmentStartMinute() + FIELD_SEPARATOR + entry.accumulatedMinutes();
    }
}
