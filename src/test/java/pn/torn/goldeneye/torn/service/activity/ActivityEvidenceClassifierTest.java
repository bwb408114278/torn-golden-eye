package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.activity.ActivityEvidence;
import pn.torn.goldeneye.torn.model.user.TornUserLastActionVO;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活跃度双证据判定纯函数测试
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.21
 */
@DisplayName("活跃度双证据判定纯函数测试")
class ActivityEvidenceClassifierTest {

    private static final long NOW = 1_000_000L;

    @Test
    @DisplayName("Online + 过期 timestamp -> 活跃（status 证据优先）")
    void shouldClassifyOnlineWithStaleTimestampAsActive() {
        TornUserLastActionVO lastAction = buildLastAction("Online", NOW - 3600);
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(lastAction, NOW);
        assertTrue(evidence.statusActive());
        assertFalse(evidence.recentAction());
        assertTrue(evidence.estimatedActive());
    }

    @Test
    @DisplayName("Idle + 过期 timestamp -> 活跃（status 证据优先）")
    void shouldClassifyIdleWithStaleTimestampAsActive() {
        TornUserLastActionVO lastAction = buildLastAction("Idle", NOW - 3600);
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(lastAction, NOW);
        assertTrue(evidence.statusActive());
        assertFalse(evidence.recentAction());
        assertTrue(evidence.estimatedActive());
    }

    @Test
    @DisplayName("Offline + 近期 timestamp -> 活跃（兼容隐藏在线状态）")
    void shouldClassifyOfflineWithRecentTimestampAsActive() {
        TornUserLastActionVO lastAction = buildLastAction("Offline", NOW - 300);
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(lastAction, NOW);
        assertFalse(evidence.statusActive());
        assertTrue(evidence.recentAction());
        assertTrue(evidence.estimatedActive());
    }

    @Test
    @DisplayName("Offline + 过期 timestamp -> 不活跃（真实离线）")
    void shouldClassifyOfflineWithStaleTimestampAsInactive() {
        TornUserLastActionVO lastAction = buildLastAction("Offline", NOW - 3600);
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(lastAction, NOW);
        assertFalse(evidence.statusActive());
        assertFalse(evidence.recentAction());
        assertFalse(evidence.estimatedActive());
    }

    @Test
    @DisplayName("null status + 近期 timestamp -> 活跃（降级为动作证据）")
    void shouldClassifyNullStatusWithRecentTimestampAsActive() {
        TornUserLastActionVO lastAction = buildLastAction(null, NOW - 300);
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(lastAction, NOW);
        assertFalse(evidence.statusActive());
        assertTrue(evidence.recentAction());
        assertTrue(evidence.estimatedActive());
    }

    @Test
    @DisplayName("null lastAction -> 不活跃但仍属于已观测")
    void shouldClassifyNullLastActionAsInactive() {
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(null, NOW);
        assertFalse(evidence.statusActive());
        assertFalse(evidence.recentAction());
        assertFalse(evidence.estimatedActive());
    }

    @Test
    @DisplayName("恰好 15 分钟边界 -> 不活跃（左闭右开）")
    void shouldClassifyExactly15MinutesAsInactive() {
        TornUserLastActionVO lastAction = buildLastAction("Offline", NOW - 900);
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(lastAction, NOW);
        assertFalse(evidence.recentAction());
        assertFalse(evidence.estimatedActive());
    }

    @Test
    @DisplayName("14 分 59 秒 -> 活跃（窗口内）")
    void shouldClassifyJustUnder15MinutesAsActive() {
        TornUserLastActionVO lastAction = buildLastAction("Offline", NOW - 899);
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(lastAction, NOW);
        assertTrue(evidence.recentAction());
        assertTrue(evidence.estimatedActive());
    }

    @Test
    @DisplayName("timestamp 为 0 -> 不活跃")
    void shouldClassifyZeroTimestampAsInactive() {
        TornUserLastActionVO lastAction = buildLastAction("Offline", 0);
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(lastAction, NOW);
        assertFalse(evidence.recentAction());
        assertFalse(evidence.estimatedActive());
    }

    @Test
    @DisplayName("timestamp 为负数 -> 不活跃")
    void shouldClassifyNegativeTimestampAsInactive() {
        TornUserLastActionVO lastAction = buildLastAction("Offline", -1);
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(lastAction, NOW);
        assertFalse(evidence.recentAction());
        assertFalse(evidence.estimatedActive());
    }

    @Test
    @DisplayName("timestamp 轻微领先本机时间 -> 不活跃（禁止未来时间戳永久活跃）")
    void shouldClassifySlightlyFutureTimestampAsInactive() {
        TornUserLastActionVO lastAction = buildLastAction("Offline", NOW + 60);
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(lastAction, NOW);
        assertFalse(evidence.recentAction());
        assertFalse(evidence.estimatedActive());
    }

    @Test
    @DisplayName("timestamp 明显异常（远超当前时间）-> 不活跃")
    void shouldClassifyAbnormallyFutureTimestampAsInactive() {
        TornUserLastActionVO lastAction = buildLastAction("Offline", NOW + 999_999_999L);
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(lastAction, NOW);
        assertFalse(evidence.recentAction());
        assertFalse(evidence.estimatedActive());
    }

    @Test
    @DisplayName("status 大小写不敏感（online/IDLE 仍判定为活跃）")
    void shouldClassifyStatusCaseInsensitively() {
        assertTrue(ActivityEvidenceClassifier.isStatusActive("online"));
        assertTrue(ActivityEvidenceClassifier.isStatusActive("IDLE"));
        assertTrue(ActivityEvidenceClassifier.isStatusActive("  Online  "));
    }

    @Test
    @DisplayName("未知 status 值 -> statusActive 为 false")
    void shouldClassifyUnknownStatusAsNotActive() {
        assertFalse(ActivityEvidenceClassifier.isStatusActive("Unknown"));
        assertFalse(ActivityEvidenceClassifier.isStatusActive(""));
        assertFalse(ActivityEvidenceClassifier.isStatusActive(null));
    }

    private static TornUserLastActionVO buildLastAction(String status, long timestamp) {
        TornUserLastActionVO vo = new TornUserLastActionVO();
        vo.setStatus(status);
        vo.setTimestamp(timestamp);
        return vo;
    }
}
