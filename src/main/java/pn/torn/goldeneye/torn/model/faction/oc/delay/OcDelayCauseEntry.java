package pn.torn.goldeneye.torn.model.faction.oc.delay;

/**
 * 单个成员的OC延误归因条目。
 * <p>
 * 只负责段起点与净阻塞累计的纯计算，不访问数据库与Torn API；
 * 原因与补充道具都是首次观测值，后续采样只更新段状态，不覆盖原因。
 *
 * @param userId             成员ID
 * @param reason             首次观测到的延误原因
 * @param itemId             同时缺失的道具ID；无则为null
 * @param segmentStartMinute 当前未闭合阻塞段的起点（epoch分钟）；无开放段时为{@link #NO_OPEN_SEGMENT}
 * @param accumulatedMinutes 已闭合阻塞段的净阻塞累计分钟
 * @author Bai
 * @version 1.6.7
 * @since 2026.10.02
 */
public record OcDelayCauseEntry(long userId,
                                OcDelayReasonEnum reason,
                                Integer itemId,
                                long segmentStartMinute,
                                int accumulatedMinutes) {
    /**
     * 无开放阻塞段。
     */
    public static final long NO_OPEN_SEGMENT = 0L;

    /**
     * 是否存在尚未结算的阻塞段。
     *
     * @return 当前段起点有效时返回true
     */
    public boolean hasOpenSegment() {
        return segmentStartMinute > NO_OPEN_SEGMENT;
    }

    /**
     * 开启阻塞段。
     *
     * @param minute 采样时刻所在的分钟桶
     * @return 段起点为采样时刻的条目
     */
    public OcDelayCauseEntry startSegment(long minute) {
        return new OcDelayCauseEntry(userId, reason, itemId, minute, accumulatedMinutes);
    }

    /**
     * 闭合阻塞段并累加时长；无开放段或闭合时刻不晚于段起点时原样返回。
     *
     * @param minute 闭合时刻所在的分钟桶
     * @return 闭合后的条目
     */
    public OcDelayCauseEntry closeSegment(long minute) {
        if (!hasOpenSegment() || minute <= segmentStartMinute) {
            return this;
        }

        return new OcDelayCauseEntry(userId, reason, itemId, NO_OPEN_SEGMENT,
                accumulatedMinutes + (int) (minute - segmentStartMinute));
    }

    /**
     * 含开放段在内的净阻塞总分钟数。
     *
     * @param minute 结算时刻所在的分钟桶
     * @return 净阻塞累计分钟
     */
    public int totalMinutes(long minute) {
        if (!hasOpenSegment() || minute <= segmentStartMinute) {
            return accumulatedMinutes;
        }

        return accumulatedMinutes + (int) (minute - segmentStartMinute);
    }
}
