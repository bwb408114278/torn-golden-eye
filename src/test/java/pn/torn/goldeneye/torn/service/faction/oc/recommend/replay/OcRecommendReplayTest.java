package pn.torn.goldeneye.torn.service.faction.oc.recommend.replay;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignFactionDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcReassignOcDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignFactionDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcReassignOcDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.service.faction.oc.planning.replay.OcPlannerReplayTestConfiguration;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcRecommendService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OC推荐真实快照只读回放：对全部已开大锅饭的帮派各取3名活跃成员，输出新口径Top-3并校验
 * 结果非空与可复现。使用最小只读上下文，不启动GoldenEyeApplication；默认不执行，
 * 仅在{@code -Doc.recommend.replay.enabled=true}时运行。回放明细同时写入
 * {@code target/oc-recommend-replay.txt}，便于归档对比。
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@Slf4j
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {OcPlannerReplayTestConfiguration.class,
        OcRecommendReplayTestConfiguration.class})
@TestPropertySource(properties = {
        "mybatis-plus.global-config.db-config.logic-delete-field=deleted",
        "mybatis-plus.global-config.db-config.logic-delete-value=1",
        "mybatis-plus.global-config.db-config.logic-not-delete-value=0",
        "mybatis-plus.type-aliases-package=pn.torn.goldeneye.repository.model"})
@EnabledIfSystemProperty(named = "oc.recommend.replay.enabled", matches = "true")
@DisplayName("OC推荐只读回放")
class OcRecommendReplayTest {
    private static final int TOP_N = 3;
    private static final int MEMBERS_PER_FACTION = 3;
    private static final Path REPORT = Path.of("target", "oc-recommend-replay.txt");

    @Autowired
    private TornOcRecommendService recommendService;
    @Autowired
    private TornFactionOcUserDAO ocUserDao;
    @Autowired
    private TornFactionOcDAO ocDao;
    @Autowired
    private TornFactionOcSlotDAO ocSlotDao;
    @Autowired
    private TornSettingOcReassignFactionDAO reassignFactionDao;
    @Autowired
    private TornSettingOcReassignOcDAO reassignOcDao;

    @Test
    @DisplayName("各帮派活跃成员Top-3非空、可复现，并统计名单外与停转队命中")
    void replayTopThreeForEnabledFactions() throws IOException {
        List<String> report = new ArrayList<>();
        List<Long> factionIds = enabledFactionIds();
        assertThat(factionIds).isNotEmpty();

        int outsideRosterHits = 0;
        int pausedHits = 0;
        int totalRecommendations = 0;
        Map<Long, Integer> nonRosterCandidates = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        long maxElapsedMillis = 0;

        for (Long factionId : factionIds) {
            Set<String> roster = rosterNames(factionId);
            nonRosterCandidates.put(factionId, countNonRosterSlots(factionId, roster));
            for (Long memberId : activeMemberIds(factionId)) {
                TornUserDO user = new TornUserDO();
                user.setId(memberId);
                user.setFactionId(factionId);

                long startedAt = System.currentTimeMillis();
                List<OcRecommendationVO> first = recommendService.recommendOcForUser(user, TOP_N, null);
                long elapsed = System.currentTimeMillis() - startedAt;
                maxElapsedMillis = Math.max(maxElapsedMillis, elapsed);
                List<OcRecommendationVO> second = recommendService.recommendOcForUser(user, TOP_N, null);

                assertThat(spots(first)).isEqualTo(spots(second));
                assertThat(first).as("帮派%s成员%s的推荐结果不应为空", factionId, memberId).isNotEmpty();

                for (OcRecommendationVO recommendation : first) {
                    boolean inRoster = roster.contains(recommendation.getOcName());
                    boolean paused = recommendation.getReadyTime() != null
                            && !recommendation.getReadyTime().isAfter(now);
                    totalRecommendations++;
                    if (!inRoster) {
                        outsideRosterHits++;
                    }
                    if (paused) {
                        pausedHits++;
                    }
                    report.add(String.format(
                            "faction=%d member=%d oc=%s rank=%d pos=%s inRoster=%s paused=%s "
                                    + "cost=%s netPerMemberDay=%s substitute=%s outcome=%s",
                            factionId, memberId, recommendation.getOcName(), recommendation.getRank(),
                            recommendation.getRecommendedPosition(), inRoster, paused,
                            recommendation.getOpportunityCost(), recommendation.getExpectedNetPerMemberDay(),
                            recommendation.getSubstituteCount(), recommendation.getOutcome()));
                }
                report.add(String.format("faction=%d member=%d elapsedMs=%d top=%s",
                        factionId, memberId, elapsed, spots(first)));
            }
        }

        report.add(String.format("SUMMARY factions=%d recommendations=%d nonRosterCandidates=%s "
                        + "outsideRosterHits=%d pausedHits=%d maxElapsedMs=%d",
                factionIds.size(), totalRecommendations, nonRosterCandidates,
                outsideRosterHits, pausedHits, maxElapsedMillis));
        Files.writeString(REPORT, String.join(System.lineSeparator(), report), StandardCharsets.UTF_8);
        report.forEach(log::info);
    }

    private List<String> spots(List<OcRecommendationVO> recommendations) {
        List<String> spots = new ArrayList<>(recommendations.size());
        for (OcRecommendationVO recommendation : recommendations) {
            spots.add(recommendation.getOcId() + "/" + recommendation.getRecommendedPosition());
        }
        return spots;
    }

    private List<Long> enabledFactionIds() {
        return reassignFactionDao.lambdaQuery()
                .eq(TornSettingOcReassignFactionDO::getDeleted, 0)
                .eq(TornSettingOcReassignFactionDO::getEnabled, true)
                .list().stream()
                .map(TornSettingOcReassignFactionDO::getFactionId)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    private Set<String> rosterNames(long factionId) {
        return reassignOcDao.lambdaQuery()
                .eq(TornSettingOcReassignOcDO::getDeleted, 0)
                .eq(TornSettingOcReassignOcDO::getFactionId, factionId)
                .eq(TornSettingOcReassignOcDO::getEnabled, true)
                .list().stream()
                .map(TornSettingOcReassignOcDO::getOcName)
                .collect(Collectors.toSet());
    }

    /**
     * 统计该帮派当前招募中OC里属于名单外且有空位的岗位数，用于说明候选池此前被剪枝的规模。
     */
    private int countNonRosterSlots(long factionId, Set<String> roster) {
        List<TornFactionOcDO> recruitList = ocDao.queryRecrutList(factionId);
        List<TornFactionOcSlotDO> slots = ocSlotDao.queryEmptySlotList(recruitList);
        Map<Long, String> nameByOcId = new HashMap<>();
        recruitList.forEach(oc -> nameByOcId.put(oc.getId(), oc.getName()));
        return (int) slots.stream()
                .filter(slot -> !roster.contains(nameByOcId.get(slot.getOcId())))
                .count();
    }

    private List<Long> activeMemberIds(long factionId) {
        Map<Long, Integer> rowsByUser = new HashMap<>();
        for (TornFactionOcUserDO row : ocUserDao.queryByFactionId(factionId)) {
            if (row.getUserId() != null) {
                rowsByUser.merge(row.getUserId(), 1, Integer::sum);
            }
        }
        return rowsByUser.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MEMBERS_PER_FACTION)
                .map(Map.Entry::getKey)
                .toList();
    }
}
