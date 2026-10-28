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
import pn.torn.goldeneye.torn.service.faction.oc.planning.replay.OcPlanningReadOnlyGuard;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcAssignService;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcRecommendService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OC推荐真实快照的只读回放取证。
 *
 * <p>用途：按技术方案11.4，用当前真实快照对新口径做一次新旧对比取证，回答「名单外OC能否被推荐」
 * 「停转队是否被挤出Top-3」「单位人天产出与业务基线是否一致」。严格成功率准入允许无候选成员返回空，
 * 回放只要求同一快照重复推荐可复现并单独统计空结果。它不是回归测试，
 * 只在显式开启{@code -Doc.recommend.replay.enabled=true}时执行，正常构建与CI不会运行。</p>
 *
 * <p>数据整洁性：回放只做SELECT，不写任何表，因此不需要{@code @Rollback}；
 * 全部读取放在{@link OcPlanningReadOnlyGuard}的REQUIRES_NEW + READ ONLY + REPEATABLE READ
 * 事务内执行，任何写操作都会直接被数据库拒绝，同时两次推荐在同一快照内比较。</p>
 *
 * <p>明细写入{@code target/oc-recommend-replay-<时间戳>.txt}：每次运行各留一份，不复写历史证据。</p>
 *
 * @author Bai
 * @version 1.9.5
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
    private static final Path REPORT_DIRECTORY = Path.of("target");
    private static final DateTimeFormatter REPORT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Autowired
    private OcPlanningReadOnlyGuard readOnlyGuard;
    @Autowired
    private TornOcRecommendService recommendService;
    @Autowired
    private TornOcAssignService assignService;
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
    @DisplayName("各帮派活跃成员Top-3可复现，并统计严格准入空结果、名单外与停转队命中")
    void replayTopThreeForEnabledFactions() throws IOException {
        readOnlyGuard.verifyReadOnlySession();
        List<String> report = readOnlyGuard.inReadOnlyTransaction(status -> collectReport());
        Path reportPath = REPORT_DIRECTORY.resolve(
                "oc-recommend-replay-" + REPORT_STAMP.format(LocalDateTime.now()) + ".txt");
        Files.writeString(reportPath, String.join(System.lineSeparator(), report), StandardCharsets.UTF_8);
        log.info("回放明细已写入 {}", reportPath.toAbsolutePath());
        report.forEach(log::info);
    }

    @Test
    @DisplayName("真实分配逐人重算且同一空位不重复分配")
    void replayAllocationForEnabledFactions() {
        readOnlyGuard.verifyReadOnlySession();
        readOnlyGuard.inReadOnlyTransaction(status -> {
            int allocated = 0;
            for (Long factionId : enabledFactionIds()) {
                Map<TornUserDO, List<TornFactionOcUserDO>> users = new LinkedHashMap<>();
                List<Long> memberIds = activeMemberIds(factionId);
                for (Long memberId : memberIds.subList(0, Math.min(2, memberIds.size()))) {
                    TornUserDO user = new TornUserDO();
                    user.setId(memberId);
                    user.setFactionId(factionId);
                    users.put(user, ocUserDao.queryByUserId(memberId));
                }
                Map<TornUserDO, OcRecommendationVO> result = assignService.assignUserList(factionId, users);
                assertThat(result.keySet()).isEqualTo(users.keySet());
                Set<String> occupied = new HashSet<>();
                for (OcRecommendationVO recommendation : result.values()) {
                    if (recommendation == null) {
                        continue;
                    }
                    allocated++;
                    assertThat(occupied.add(recommendation.getOcId() + "/" + recommendation.getRecommendedPosition()))
                            .as("帮派%s同一空位不得重复分配", factionId).isTrue();
                }
            }
            assertThat(allocated).isPositive();
            log.info("真实分配回放完成, 有效分配数={}", allocated);
            return null;
        });
    }

    /**
     * 在只读事务内执行回放并返回明细行。
     */
    private List<String> collectReport() {
        List<String> report = new ArrayList<>();
        List<Long> factionIds = enabledFactionIds();
        assertThat(factionIds).isNotEmpty();

        int outsideRosterHits = 0;
        int pausedHits = 0;
        int noEligibleMembers = 0;
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
                if (first.isEmpty()) {
                    noEligibleMembers++;
                    report.add(String.format("faction=%d member=%d eligible=0（严格成功率准入）", factionId, memberId));
                }

                for (OcRecommendationVO recommendation : first) {
                    boolean inRoster = roster.contains(recommendation.getOcName());
                    boolean paused = recommendation.getReadyTime() != null
                            && !recommendation.getReadyTime().isAfter(now);
                    if (!inRoster) {
                        outsideRosterHits++;
                    }
                    if (paused) {
                        pausedHits++;
                    }
                    report.add(String.format(
                            "faction=%d member=%d oc=%s rank=%d pos=%s inRoster=%s paused=%s "
                                    + "rating=%s netPerMemberDay=%s substitute=%s outcome=%s",
                            factionId, memberId, recommendation.getOcName(), recommendation.getRank(),
                            recommendation.getRecommendedPosition(), inRoster, paused,
                            recommendation.getRecommendScore(), recommendation.getExpectedNetPerMemberDay(),
                            recommendation.getSubstituteCount(), recommendation.getOutcome()));
                }
                report.add(String.format("faction=%d member=%d elapsedMs=%d top=%s",
                        factionId, memberId, elapsed, spots(first)));
            }
        }

        report.add(String.format("SUMMARY factions=%d recommendations=%d nonRosterCandidates=%s "
                        + "outsideRosterHits=%d pausedHits=%d noEligibleMembers=%d maxElapsedMs=%d",
                factionIds.size(), report.stream().filter(line -> line.contains(" rating=")).count(),
                nonRosterCandidates, outsideRosterHits, pausedHits, noEligibleMembers, maxElapsedMillis));
        return report;
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
                .filter(Objects::nonNull)
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
