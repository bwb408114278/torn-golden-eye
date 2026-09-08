package pn.torn.goldeneye.torn.service.stocks.alert.alpha.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDailySnapshotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDailySnapshotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 伪绛栫暐鏃ョ嚎鏀剁洏鏈嶅姟銆? * <p>
 * 鏈湇鍔″尯鍒嗕笁绫诲姩浣?閬垮厤鍘嗗彶鏃ョ嚎鍐欏叆杩涘叆杞璧勯噾浜嬪姟:
 * <ol>
 *   <li>{@link #loadDailyCloses(LocalDate)}: 姝ｅ紡鍐崇瓥璇诲彇,鍙涓斿畬鏁存€т笉婊¤冻鏃秄ail-closed杩斿洖绌虹粨鏋?</li>
 *   <li>{@link #buildDailyClosesForEndedDay(LocalDateTime)}: 鑷劧鏃ユ渶鍚庝竴涓?5鍒嗛挓妗剁粨鏉熷悗鐨勫揩鐓ф瀯寤?</li>
 *   <li>{@link #buildDailyCloses(LocalDate)}: 棰勫～涓庣己鍙ｄ慨澶?鍙缂哄け鎴栦笉瀹屾暣鏃ユ湡鎵归噺鍐欏叆銆?/li>
 * </ol>
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlphaDailyCloseService {
    /**
     * 鏀剁洏bar鏋勫缓鐗堟湰銆?     */
    private static final String BAR_BUILD_VERSION = Stock15mBarBuildService.BUILD_VERSION;
    /**
     * 鍐崇瓥绐楀彛鍦ㄩ鐑叡鍚屾湁鏁堟棩涔嬪棰濆鍥炵湅鐨勮嚜鐒舵棩鏁伴噺銆?     */
    private static final int HISTORY_BUFFER_DAYS = 20;
    /**
     * 鍗曟壒鍐欏叆鏉℃暟涓婇檺,涓庨」鐩棦鏈夋壒閲廢PSERT绾﹀畾涓€鑷淬€?     */
    private static final int BATCH_SIZE = 500;
    /**
     * 鑷劧鏃ユ渶鍚庝竴涓?5鍒嗛挓妗惰捣鐐广€?     */
    private static final LocalTime LAST_DAY_BUCKET_START = LocalTime.of(23, 45);
    /**
     * 鍥哄畾鑲＄エ姹犳垚鍛橀泦鍚堛€?     */
    private static final Set<Integer> MEMBER_IDS = Set.copyOf(StockAlphaRuleDefinition.stockUniverse());

    /**
     * 15鍒嗛挓bar鏁版嵁璁块棶瀵硅薄銆?     */
    private final TornStockMarketBar15mDAO barDao;
    /**
     * 伪鏃ョ嚎蹇収鏁版嵁璁块棶瀵硅薄銆?     */
    private final TornStockAlphaDailySnapshotDAO snapshotDao;

    /**
     * 璇诲彇鎸囧畾缁撴潫鏃ユ湡鍓嶇殑瀹屾暣鏃ョ嚎鏀剁洏鏁版嵁銆?     * <p>
     * 鍙秷璐瑰凡鎸佷箙鍖栫殑瀹屾暣蹇収:浠讳竴鏃ョ己澶便€佹垚鍛橀泦鍚堜笉涓€鑷存垨蹇収瀛楁闈炴硶鏃惰繑鍥炵┖缁撴灉,
     * 涓嶈Е鍙戝巻鍙查噸绠?涓嶄骇鐢熶换浣曞啓鍏?渚涜疆娆′簨鍔″唴瀹夊叏璋冪敤銆?     *
     * @param endDate 缁撴潫鏃ユ湡(闂尯闂翠笂鐣?
     * @return 鎸夋棩鏈熷拰鑲＄エID鍒嗙粍鐨勬敹鐩樼粨鏋?鏁版嵁涓嶅畬鏁存椂涓虹┖
     */
    public Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> loadDailyCloses(
            LocalDate endDate) {
        DateRange range = DateRange.endingAt(endDate);
        List<TornStockAlphaDailySnapshotDO> storedSnapshots = selectSnapshots(range);
        if (!isRangeComplete(storedSnapshots, range)) {
            log.warn("伪鏃ョ嚎蹇収涓嶅畬鏁?鏈疆涓嶅弬涓庡喅绛? endDate={}, startDate={}", range.end(), range.start());
            return Map.of();
        }
        return toCloseResults(storedSnapshots);
    }

    /**
     * 鍦ㄨ嚜鐒舵棩鏈€鍚庝竴涓?5鍒嗛挓妗剁粨鏉熷悗鏋勫缓褰撴棩鍙婂巻鍙茬己鍙ｇ殑鏃ョ嚎鏀剁洏蹇収銆?     *
     * @param roundTime 宸茬粨鏉熺殑杞bar璧风偣
     * @return 鏈鍐欏叆鐨勫揩鐓ф潯鏁?     */
    public int buildDailyClosesForEndedDay(LocalDateTime roundTime) {
        if (roundTime == null || !LAST_DAY_BUCKET_START.equals(roundTime.toLocalTime())) {
            return 0;
        }
        return buildDailyCloses(roundTime.toLocalDate());
    }

    /**
     * 鏋勫缓鎴栦慨澶嶆寚瀹氱粨鏉熸棩鏈熷墠鐨勬棩绾挎敹鐩樺揩鐓с€?     * <p>
     * 鍙缂哄け鎴栦笉瀹屾暣鐨勮嚜鐒舵棩鎵弿bar骞舵壒閲廢PSERT;鍏ㄩ儴鏃ユ湡宸插畬鏁存椂涓嶈鍙朾ar銆佷笉鍐欏叆銆?     * 閮ㄥ垎鍐欏叆涓嶄細鎶婅鏃ユ湡鏍囪涓哄畬鏁?涓嬩竴娆℃瀯寤轰粛浼氶噸鏂拌ˉ榻愩€?     *
     * @param endDate 缁撴潫鏃ユ湡(闂尯闂翠笂鐣?
     * @return 鏈鍐欏叆鐨勫揩鐓ф潯鏁?     */
    public int buildDailyCloses(LocalDate endDate) {
        DateRange range = DateRange.endingAt(endDate);
        List<LocalDate> missingDates = missingDates(selectSnapshots(range), range);
        if (missingDates.isEmpty()) {
            return 0;
        }
        LocalDate from = missingDates.stream().min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = missingDates.stream().max(Comparator.naturalOrder()).orElseThrow();
        List<TornStockMarketBar15mDO> bars = barDao.selectByStocksAndTimeRange(
                StockAlphaRuleDefinition.stockUniverse(), from.atStartOfDay(), to.plusDays(1).atStartOfDay(),
                BAR_BUILD_VERSION);
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> computed = calculateDaily(bars);
        List<TornStockAlphaDailySnapshotDO> pending = new ArrayList<>();
        for (LocalDate date : missingDates) {
            Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> daily = computed.get(date);
            if (daily != null && isCompleteMemberSet(daily.keySet())) {
                daily.values().forEach(close -> pending.add(buildSnapshot(close)));
            } else {
                log.warn("伪鏃ョ嚎鏀剁洏璁＄畻鏈鐩栧畬鏁磋偂绁ㄦ睜,鏈涓嶅啓鍏? businessDate={}", date);
            }
        }
        int written = batchInsert(pending);
        log.info("伪鏃ョ嚎蹇収鏋勫缓瀹屾垚: endDate={}, 缂哄け鏃ユ湡鏁?{}, 鍐欏叆鏉℃暟={}", range.end(), missingDates.size(), written);
        return written;
    }

    /**
     * 鎵归噺鍐欏叆鎸囧畾鎺掑悕鏃ユ湡鐨勬帓鍚嶇粨鏋溿€?     *
     * @param rankingDate 鎺掑悕鏃ユ湡
     * @param closes      璇ユ棩鏈熺殑鏀剁洏缁撴灉
     * @param rankings    鎺掑悕缁撴灉
     */
    public void persistRankings(LocalDate rankingDate,
                                Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> closes,
                                List<StockAlphaRankingResult> rankings) {
        if (rankingDate == null || closes == null || closes.isEmpty() || rankings == null || rankings.isEmpty()) {
            return;
        }
        List<TornStockAlphaDailySnapshotDO> pending = new ArrayList<>();
        for (StockAlphaRankingResult ranking : rankings) {
            StockAlphaDailyCloseCalculator.CloseResult close = closes.get(ranking.stocksId());
            if (close == null || !rankingDate.equals(close.businessDate())) {
                log.warn("伪鎺掑悕缂哄皯瀵瑰簲鏀剁洏蹇収,璺宠繃璇ヨ偂绁ㄦ帓鍚嶅啓鍏? rankingDate={}, stocksId={}",
                        rankingDate, ranking.stocksId());
                continue;
            }
            pending.add(buildRankingSnapshot(close, ranking));
        }
        batchInsert(pending);
    }

    /**
     * 鎸夋棩鏈熻寖鍥存煡璇㈠綋鍓嶇増鏈殑鏃ョ嚎蹇収銆?     *
     * @param range 鏃ユ湡鑼冨洿
     * @return 鏃ョ嚎蹇収
     */
    private List<TornStockAlphaDailySnapshotDO> selectSnapshots(DateRange range) {
        return snapshotDao.selectByDateRange(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION,
                StockAlphaRuleDefinition.RULE_VERSION, range.start(), range.end());
    }

    /**
     * 鍒ゆ柇宸叉寔涔呭寲蹇収鏄惁瀹屾暣瑕嗙洊鏃ユ湡鑼冨洿鍐呯殑姣忎竴澶╁拰鍏ㄩ儴鑲＄エ鎴愬憳銆?     * <p>
     * 鏍￠獙鑼冨洿鏄惧紡鍖呭惈{@code startDate}鍒皗@code endDate}鐨勬瘡涓嚜鐒舵棩,鑰屼笉鏄彧瀵瑰凡鍑虹幇鐨勬棩鏈熷垽鏂?
     * 姣忎竴澶╃殑鎴愬憳闆嗗悎蹇呴』涓庡浐瀹氳偂绁ㄦ睜瀹屽叏鐩哥瓑,浠庤€屽悓鏃舵嫆缁濈己澶辨垚鍛樸€侀敊璇垚鍛樺拰閲嶅鎴愬憳銆?     *
     * @param snapshots 宸叉寔涔呭寲鐨勬棩绾垮揩鐓?     * @param range     鏃ユ湡鑼冨洿
     * @return 姣忎竴澶╅兘瀹屾暣涓斿悎娉曟椂杩斿洖true
     */
    private boolean isRangeComplete(List<TornStockAlphaDailySnapshotDO> snapshots, DateRange range) {
        return missingDates(snapshots, range).isEmpty();
    }

    /**
     * 璁＄畻鏃ユ湡鑼冨洿鍐呯己澶辨垨涓嶅畬鏁寸殑鑷劧鏃ャ€?     *
     * @param snapshots 宸叉寔涔呭寲鐨勬棩绾垮揩鐓?     * @param range     鏃ユ湡鑼冨洿
     * @return 缂哄け鎴栦笉瀹屾暣鐨勮嚜鐒舵棩
     */
    private List<LocalDate> missingDates(List<TornStockAlphaDailySnapshotDO> snapshots, DateRange range) {
        Map<LocalDate, Set<Integer>> validMembersByDate = snapshots == null
                ? Map.of()
                : snapshots.stream()
                .filter(StockAlphaDailyCloseService::isUsableSnapshot)
                .filter(snapshot -> range.contains(snapshot.getBusinessDate()))
                .collect(Collectors.groupingBy(TornStockAlphaDailySnapshotDO::getBusinessDate,
                        Collectors.mapping(TornStockAlphaDailySnapshotDO::getStocksId, Collectors.toSet())));
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate date = range.start(); !date.isAfter(range.end()); date = date.plusDays(1)) {
            if (!isCompleteMemberSet(validMembersByDate.get(date))) {
                missing.add(date);
            }
        }
        return missing;
    }

    /**
     * 鍒ゆ柇鎴愬憳闆嗗悎鏄惁涓庡浐瀹氳偂绁ㄦ睜瀹屽叏鐩哥瓑銆?     *
     * @param stocksIds 鎴愬憳闆嗗悎
     * @return 瀹屽叏鐩哥瓑鏃惰繑鍥瀟rue
     */
    private boolean isCompleteMemberSet(Set<Integer> stocksIds) {
        return stocksIds != null && stocksIds.equals(MEMBER_IDS);
    }

    /**
     * 鍒ゆ柇蹇収鏄惁鍙綔涓哄叡鍚屾湁鏁堟棩绾挎暟鎹€?     *
     * @param snapshot 鏃ョ嚎蹇収
     * @return 鐗堟湰銆佹湁鏁堟€с€佷环鏍煎拰鏉ユ簮bar瀛楁鍧囧悎娉曟椂杩斿洖true
     */
    private static boolean isUsableSnapshot(TornStockAlphaDailySnapshotDO snapshot) {
        return snapshot != null
                && Boolean.TRUE.equals(snapshot.getCommonValid())
                && snapshot.getStocksId() != null
                && snapshot.getBusinessDate() != null
                && snapshot.getClosePrice() != null
                && snapshot.getClosePrice().signum() > 0
                && snapshot.getSourceBarId() != null
                && snapshot.getSourceBarStartTime() != null
                && StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION.equals(snapshot.getStockUniverseVersion())
                && StockAlphaRuleDefinition.RULE_VERSION.equals(snapshot.getAlphaRuleVersion());
    }

    /**
     * 鎸夎嚜鐒舵棩鍜岃偂绁↖D璁＄畻鏈€鍚庡彲鐢╞ar鐨勬敹鐩樼粨鏋溿€?     *
     * @param bars 15鍒嗛挓bar
     * @return 鎸夋棩鏈熷拰鑲＄エID鍒嗙粍鐨勬敹鐩樼粨鏋?     */
    private Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> calculateDaily(
            List<TornStockMarketBar15mDO> bars) {
        Map<LocalDate, List<TornStockMarketBar15mDO>> barsByDate = bars.stream()
                .filter(bar -> bar != null && bar.getStocksId() != null && bar.getBarStartTime() != null)
                .collect(Collectors.groupingBy(bar -> bar.getBarStartTime().toLocalDate()));
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> result = new HashMap<>();
        barsByDate.forEach((date, dailyBars) -> {
            Map<Integer, List<TornStockMarketBar15mDO>> byStock = dailyBars.stream()
                    .collect(Collectors.groupingBy(TornStockMarketBar15mDO::getStocksId));
            Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> daily = new HashMap<>();
            byStock.forEach((stocksId, stockBars) -> {
                StockAlphaDailyCloseCalculator.CloseResult close =
                        StockAlphaDailyCloseCalculator.calculate(date, stockBars);
                if (close != null) {
                    daily.put(stocksId, close);
                }
            });
            result.put(date, daily);
        });
        return result;
    }

    /**
     * 灏嗗凡鏍￠獙瀹屾暣鐨勬棩绾垮揩鐓ц浆鎹负鎸夋棩鏈熷拰鑲＄エID绱㈠紩鐨勬敹鐩樼粨鏋溿€?     *
     * @param snapshots 鏃ョ嚎蹇収
     * @return 鎸夋棩鏈熷拰鑲＄エID鍒嗙粍鐨勬敹鐩樼粨鏋?     */
    private Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> toCloseResults(
            List<TornStockAlphaDailySnapshotDO> snapshots) {
        return snapshots.stream()
                .filter(StockAlphaDailyCloseService::isUsableSnapshot)
                .collect(Collectors.groupingBy(TornStockAlphaDailySnapshotDO::getBusinessDate,
                        Collectors.toMap(TornStockAlphaDailySnapshotDO::getStocksId,
                                snapshot -> new StockAlphaDailyCloseCalculator.CloseResult(
                                        snapshot.getStocksId(), snapshot.getBusinessDate(), snapshot.getClosePrice(),
                                        snapshot.getSourceBarId(), snapshot.getSourceBarStartTime()))));
    }

    /**
     * 鍒嗘壒鍐欏叆鏃ョ嚎蹇収骞舵牳瀵圭敓鏁堣鏁般€?     *
     * @param pending 寰呭啓鍏ュ揩鐓?     * @return 瀹為檯鍐欏叆鏉℃暟
     */
    private int batchInsert(List<TornStockAlphaDailySnapshotDO> pending) {
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (int from = 0; from < pending.size(); from += BATCH_SIZE) {
            List<TornStockAlphaDailySnapshotDO> batch =
                    pending.subList(from, Math.min(from + BATCH_SIZE, pending.size()));
            written += snapshotDao.batchInsertIgnoreConflict(batch);
        }
        if (written != pending.size()) {
            log.warn("伪鏃ョ嚎蹇収鎵归噺鍐欏叆鏉℃暟涓庨鏈熶笉涓€鑷?缂哄け鏃ユ湡灏嗗湪涓嬫鏋勫缓閲嶆柊琛ラ綈: expected={}, actual={}",
                    pending.size(), written);
        }
        return written;
    }

    /**
     * 鏋勫缓鏃ョ嚎蹇収銆?     *
     * @param close 鏀剁洏缁撴灉
     * @return 鏃ョ嚎蹇収
     */
    private TornStockAlphaDailySnapshotDO buildSnapshot(StockAlphaDailyCloseCalculator.CloseResult close) {
        TornStockAlphaDailySnapshotDO snapshot = new TornStockAlphaDailySnapshotDO();
        snapshot.setStocksId(close.stocksId());
        snapshot.setBusinessDate(close.businessDate());
        snapshot.setClosePrice(close.closePrice());
        snapshot.setSourceBarId(close.sourceBarId());
        snapshot.setSourceBarStartTime(close.sourceBarStartTime());
        snapshot.setStockUniverseVersion(StockAlphaRuleDefinition.STOCK_UNIVERSE_VERSION);
        snapshot.setAlphaRuleVersion(StockAlphaRuleDefinition.RULE_VERSION);
        snapshot.setCommonValid(true);
        return snapshot;
    }

    /**
     * 鏋勫缓鎼哄甫鎺掑悕瀛楁鐨勬棩绾垮揩鐓с€?     *
     * @param close   鏀剁洏缁撴灉
     * @param ranking 鎺掑悕缁撴灉
     * @return 鏃ョ嚎蹇収
     */
    private TornStockAlphaDailySnapshotDO buildRankingSnapshot(StockAlphaDailyCloseCalculator.CloseResult close,
                                                               StockAlphaRankingResult ranking) {
        TornStockAlphaDailySnapshotDO snapshot = buildSnapshot(close);
        snapshot.setR20(ranking.r20());
        snapshot.setR1(ranking.r1());
        snapshot.setR20Rank(ranking.r20Rank());
        snapshot.setR1Rank(ranking.r1Rank());
        snapshot.setR20Normalized(ranking.r20Rank());
        snapshot.setR1Normalized(ranking.r1Rank());
        snapshot.setAlphaScore(ranking.alphaScore());
        snapshot.setRankPosition(ranking.rankPosition());
        return snapshot;
    }

    /**
     * 鏃ョ嚎鍐崇瓥绐楀彛鐨勯棴鍖洪棿鏃ユ湡鑼冨洿銆?     *
     * @param start 璧峰鏃ユ湡
     * @param end   缁撴潫鏃ユ湡
     */
    private record DateRange(LocalDate start, LocalDate end) {
        /**
         * 鎸夌粨鏉熸棩鏈熸瀯寤哄喅绛栫獥鍙ｃ€?         *
         * @param endDate 缁撴潫鏃ユ湡
         * @return 鏃ユ湡鑼冨洿
         */
        private static DateRange endingAt(LocalDate endDate) {
            if (endDate == null) {
                throw new IllegalArgumentException("伪鏃ョ嚎缁撴潫鏃ユ湡涓嶈兘涓虹┖");
            }
            long windowDays = StockAlphaRuleDefinition.WARMUP_COMMON_DAYS + (long) HISTORY_BUFFER_DAYS;
            return new DateRange(endDate.minusDays(windowDays), endDate);
        }

        /**
         * 鍒ゆ柇鏃ユ湡鏄惁钀藉湪鑼冨洿鍐呫€?         *
         * @param date 鏃ユ湡
         * @return 钀藉湪闂尯闂村唴鏃惰繑鍥瀟rue
         */
        private boolean contains(LocalDate date) {
            return date != null && !date.isBefore(start) && !date.isAfter(end);
        }
    }
}
