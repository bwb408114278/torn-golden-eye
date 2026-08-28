package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.activity.ActivityEvidence;
import pn.torn.goldeneye.torn.model.user.TornUserLastActionVO;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活跃度V3互斥证据判定纯函数测试
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.07.21
 */
@DisplayName("活跃度V3互斥证据判定纯函数测试")
class ActivityEvidenceClassifierTest {

    private static final long NOW = 1_000_000L;

    @Test
    @DisplayName("Online + 过期 timestamp -> 有效活跃（在线证据保留，不构成 idle）")
    void shouldClassifyOnlineWithStaleTimestampAsActive() {
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(
                buildLastAction("Online", NOW - 3600), NOW);
        assertTrue(evidence.onlineActive());
        assertFalse(evidence.recentAction());
        assertFalse(evidence.idleOnly());
        assertTrue(evidence.effectiveActive());
    }

    @Test
    @DisplayName("Idle + 过期 timestamp -> 仅 idle-only，不再计入有效活跃（V3 关键口径）")
    void shouldClassifyIdleWithStaleTimestampAsIdleOnly() {
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(
                buildLastAction("Idle", NOW - 3600), NOW);
        assertFalse(evidence.onlineActive());
        assertFalse(evidence.recentAction());
        assertTrue(evidence.idleOnly());
        assertFalse(evidence.effectiveActive());
    }

    @Test
    @DisplayName("Idle + 近期 timestamp -> 有效活跃且不重复记入 idle")
    void shouldClassifyIdleWithRecentTimestampAsActiveOnly() {
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(
                buildLastAction("Idle", NOW - 300), NOW);
        assertFalse(evidence.onlineActive());
        assertTrue(evidence.recentAction());
        assertFalse(evidence.idleOnly());
        assertTrue(evidence.effectiveActive());
    }

    @Test
    @DisplayName("Offline + 近期 timestamp -> 有效活跃（兼容隐藏在线状态）")
    void shouldClassifyOfflineWithRecentTimestampAsActive() {
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(
                buildLastAction("Offline", NOW - 300), NOW);
        assertFalse(evidence.onlineActive());
        assertTrue(evidence.recentAction());
        assertFalse(evidence.idleOnly());
        assertTrue(evidence.effectiveActive());
    }

    @Test
    @DisplayName("Offline + 过期 timestamp -> 静默（全部证据为 false）")
    void shouldClassifyOfflineWithStaleTimestampAsSilent() {
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(
                buildLastAction("Offline", NOW - 3600), NOW);
        assertFalse(evidence.onlineActive());
        assertFalse(evidence.recentAction());
        assertFalse(evidence.idleOnly());
        assertFalse(evidence.effectiveActive());
    }

    @Test
    @DisplayName("null lastAction -> 静默但仍属于已观测")
    void shouldClassifyNullLastActionAsSilent() {
        ActivityEvidence evidence = ActivityEvidenceClassifier.classifyActivity(null, NOW);
        assertFalse(evidence.onlineActive());
        assertFalse(evidence.recentAction());
        assertFalse(evidence.idleOnly());
        assertFalse(evidence.effectiveActive());
    }

    @Test
    @DisplayName("恰好 15 分钟边界 -> 不活跃（左闭右开）；14分59秒 -> 活跃")
    void shouldClassifyRecentActionWindowBoundaries() {
        assertFalse(ActivityEvidenceClassifier.classifyActivity(
                buildLastAction("Offline", NOW - 900), NOW).recentAction());
        assertTrue(ActivityEvidenceClassifier.classifyActivity(
                buildLastAction("Offline", NOW - 899), NOW).recentAction());
    }

    @Test
    @DisplayName("timestamp 为 0/负数/未来 -> 不活跃（fail-closed）")
    void shouldClassifyInvalidTimestampsAsInactive() {
        assertFalse(ActivityEvidenceClassifier.classifyActivity(
                buildLastAction("Offline", 0), NOW).recentAction());
        assertFalse(ActivityEvidenceClassifier.classifyActivity(
                buildLastAction("Offline", -1), NOW).recentAction());
        assertFalse(ActivityEvidenceClassifier.classifyActivity(
                buildLastAction("Offline", NOW + 60), NOW).recentAction());
        assertFalse(ActivityEvidenceClassifier.classifyActivity(
                buildLastAction("Offline", NOW + 999_999_999L), NOW).recentAction());
    }

    @Test
    @DisplayName("status 大小写与空白不敏感（online/IDLE/前后空白）")
    void shouldClassifyStatusCaseAndWhitespaceInsensitively() {
        assertTrue(ActivityEvidenceClassifier.isOnlineStatus("online"));
        assertTrue(ActivityEvidenceClassifier.isOnlineStatus("  Online  "));
        assertTrue(ActivityEvidenceClassifier.isIdleStatus("IDLE"));
        assertTrue(ActivityEvidenceClassifier.isIdleStatus(" idle "));
        assertFalse(ActivityEvidenceClassifier.isOnlineStatus("Idle"));
        assertFalse(ActivityEvidenceClassifier.isIdleStatus("Online"));
    }

    @Test
    @DisplayName("未知/空 status -> 不构成 online 或 idle")
    void shouldClassifyUnknownStatusAsNeither() {
        assertFalse(ActivityEvidenceClassifier.isOnlineStatus("Unknown"));
        assertFalse(ActivityEvidenceClassifier.isIdleStatus("Unknown"));
        assertFalse(ActivityEvidenceClassifier.isOnlineStatus(""));
        assertFalse(ActivityEvidenceClassifier.isIdleStatus(""));
        assertFalse(ActivityEvidenceClassifier.isOnlineStatus(null));
        assertFalse(ActivityEvidenceClassifier.isIdleStatus(null));
    }

    private static TornUserLastActionVO buildLastAction(String status, long timestamp) {
        TornUserLastActionVO vo = new TornUserLastActionVO();
        vo.setStatus(status);
        vo.setTimestamp(timestamp);
        return vo;
    }
}
