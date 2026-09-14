package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockNoticeAuditMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * 股票通知审计DAO测试,覆盖批量状态更新、领取、租约恢复与α换仓组级回写的空入参保护与透传。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.07.28
 */
@DisplayName("股票通知审计DAO测试")
@ExtendWith(MockitoExtension.class)
class TornStockNoticeAuditDAOTest {

    /**
     * 测试用领取标识。
     */
    private static final String CLAIM_TOKEN = "claim-token-1";
    /**
     * 测试用α换仓关联标识。
     */
    private static final String ASSOCIATION_ID = "ALPHA_REBALANCE:1";
    /**
     * 测试用统一业务时间。
     */
    private static final LocalDateTime BUSINESS_NOW = LocalDateTime.of(2026, 9, 13, 11, 0);

    @Mock
    private TornStockNoticeAuditMapper mapper;

    @InjectMocks
    private TornStockNoticeAuditDAO dao;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(dao, "baseMapper", mapper);
    }

    @Test
    @DisplayName("批量标记最终失败_空通知列表或空业务时间不调用Mapper")
    void markFinalByIds_emptyNoticeIds_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.markFinalByIds(List.of(), "关联批次不存在", BUSINESS_NOW));
        assertEquals(0, dao.markFinalByIds(List.of(1L), "关联批次不存在", null));
        verify(mapper, never()).markFinalByIds(any(), any(), any());
    }

    @Test
    @DisplayName("批量标记成功_空通知列表不调用Mapper")
    void markSentByIds_emptyNoticeIds_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.markSentByIds(List.of(), CLAIM_TOKEN, BUSINESS_NOW));
        verify(mapper, never()).markSentByIds(any(), any(), any());
    }

    @Test
    @DisplayName("批量标记发送失败_空通知列表不调用Mapper")
    void markSendFailedByIds_emptyNoticeIds_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.markSendFailedByIds(List.of(), CLAIM_TOKEN, "发送失败", BUSINESS_NOW));
        verify(mapper, never()).markSendFailedByIds(any(), any(), any(), any());
    }

    @Test
    @DisplayName("领取通知_空列表或空领取标识不调用Mapper")
    void claimByIds_emptyInput_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.claimByIds(List.of(), CLAIM_TOKEN, BUSINESS_NOW));
        assertEquals(0, dao.claimByIds(List.of(1L), null, BUSINESS_NOW));
        verify(mapper, never()).claimByIds(any(), any(), any());
    }

    @Test
    @DisplayName("关联组领取_空关联标识或空领取标识不调用Mapper")
    void claimByRebalanceAssociationId_blankInput_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.claimByRebalanceAssociationId(null, CLAIM_TOKEN, BUSINESS_NOW));
        assertEquals(0, dao.claimByRebalanceAssociationId(ASSOCIATION_ID, " ", BUSINESS_NOW));
        verify(mapper, never()).claimByRebalanceAssociationId(any(), any(), any());
    }

    @Test
    @DisplayName("释放领取_空领取标识不调用Mapper")
    void releaseClaim_blankToken_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.releaseClaim(null, BUSINESS_NOW));
        verify(mapper, never()).releaseClaim(any(), any());
    }

    @Test
    @DisplayName("租约恢复_空截止时间不调用Mapper")
    void recoverStaleClaims_nullStaleBefore_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.recoverStaleClaims(null, BUSINESS_NOW));
        verify(mapper, never()).recoverStaleClaims(any(), any());
    }

    @Test
    @DisplayName("组级成功/失败/收敛回写_空关联标识或空组状态不调用Mapper")
    void groupWriteback_blankInput_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.markRebalanceGroupSent(null, CLAIM_TOKEN, BUSINESS_NOW));
        assertEquals(0, dao.markRebalanceGroupFailed(ASSOCIATION_ID, "", "原因", BUSINESS_NOW));
        assertEquals(0, dao.convergeOwnedRebalanceGroup(ASSOCIATION_ID, null, "INCONSISTENT", "原因", BUSINESS_NOW));
        assertEquals(0, dao.convergeOwnedRebalanceGroup(ASSOCIATION_ID, CLAIM_TOKEN, null, "原因", BUSINESS_NOW));
        assertEquals(0, dao.convergeUnclaimedRebalanceGroup(ASSOCIATION_ID, null, "原因", BUSINESS_NOW));
        verify(mapper, never()).markRebalanceGroupSent(any(), any(), any());
        verify(mapper, never()).markRebalanceGroupFailed(any(), any(), any(), any());
        verify(mapper, never()).convergeOwnedRebalanceGroup(any(), any(), any(), any(), any());
        verify(mapper, never()).convergeUnclaimedRebalanceGroup(any(), any(), any(), any());
    }

    @Test
    @DisplayName("批量标记最终失败_非空列表委托Mapper并返回更新行数")
    void markFinalByIds_nonEmptyNoticeIds_delegatesAndReturnsAffectedRows() {
        when(mapper.markFinalByIds(List.of(1L, 2L), "关联批次不存在", BUSINESS_NOW)).thenReturn(2);

        assertEquals(2, dao.markFinalByIds(List.of(1L, 2L), "关联批次不存在", BUSINESS_NOW));
        verify(mapper).markFinalByIds(List.of(1L, 2L), "关联批次不存在", BUSINESS_NOW);
    }

    @Test
    @DisplayName("批量标记成功_非空列表委托Mapper并返回更新行数")
    void markSentByIds_nonEmptyNoticeIds_delegatesAndReturnsAffectedRows() {
        when(mapper.markSentByIds(List.of(1L, 2L), CLAIM_TOKEN, BUSINESS_NOW)).thenReturn(2);

        assertEquals(2, dao.markSentByIds(List.of(1L, 2L), CLAIM_TOKEN, BUSINESS_NOW));
        verify(mapper).markSentByIds(List.of(1L, 2L), CLAIM_TOKEN, BUSINESS_NOW);
    }

    @Test
    @DisplayName("批量标记发送失败_非空列表委托Mapper并返回更新行数")
    void markSendFailedByIds_nonEmptyNoticeIds_delegatesAndReturnsAffectedRows() {
        when(mapper.markSendFailedByIds(List.of(1L, 2L), CLAIM_TOKEN, "HTTP状态非2xx", BUSINESS_NOW))
                .thenReturn(2);

        assertEquals(2, dao.markSendFailedByIds(List.of(1L, 2L), CLAIM_TOKEN, "HTTP状态非2xx", BUSINESS_NOW));
        verify(mapper).markSendFailedByIds(List.of(1L, 2L), CLAIM_TOKEN, "HTTP状态非2xx", BUSINESS_NOW);
    }

    @Test
    @DisplayName("领取通知_非空列表委托Mapper并返回领取行数")
    void claimByIds_nonEmptyNoticeIds_delegatesAndReturnsAffectedRows() {
        when(mapper.claimByIds(List.of(1L, 2L), CLAIM_TOKEN, BUSINESS_NOW)).thenReturn(2);

        assertEquals(2, dao.claimByIds(List.of(1L, 2L), CLAIM_TOKEN, BUSINESS_NOW));
        verify(mapper).claimByIds(List.of(1L, 2L), CLAIM_TOKEN, BUSINESS_NOW);
    }

    @Test
    @DisplayName("组级回写_非空入参委托Mapper并返回真实行数")
    void groupWriteback_nonBlankInput_delegatesToMapper() {
        when(mapper.markRebalanceGroupSent(ASSOCIATION_ID, CLAIM_TOKEN, BUSINESS_NOW)).thenReturn(2);
        when(mapper.markRebalanceGroupFailed(ASSOCIATION_ID, CLAIM_TOKEN, "原因", BUSINESS_NOW)).thenReturn(2);
        when(mapper.convergeOwnedRebalanceGroup(ASSOCIATION_ID, CLAIM_TOKEN, "INCONSISTENT", "原因",
                BUSINESS_NOW)).thenReturn(2);
        when(mapper.convergeUnclaimedRebalanceGroup(ASSOCIATION_ID, "INCONSISTENT", "原因",
                BUSINESS_NOW)).thenReturn(2);

        assertEquals(2, dao.markRebalanceGroupSent(ASSOCIATION_ID, CLAIM_TOKEN, BUSINESS_NOW));
        assertEquals(2, dao.markRebalanceGroupFailed(ASSOCIATION_ID, CLAIM_TOKEN, "原因", BUSINESS_NOW));
        assertEquals(2, dao.convergeOwnedRebalanceGroup(ASSOCIATION_ID, CLAIM_TOKEN, "INCONSISTENT", "原因",
                BUSINESS_NOW));
        assertEquals(2, dao.convergeUnclaimedRebalanceGroup(ASSOCIATION_ID, "INCONSISTENT", "原因",
                BUSINESS_NOW));
        verify(mapper).markRebalanceGroupSent(ASSOCIATION_ID, CLAIM_TOKEN, BUSINESS_NOW);
        verify(mapper).markRebalanceGroupFailed(ASSOCIATION_ID, CLAIM_TOKEN, "原因", BUSINESS_NOW);
        verify(mapper).convergeOwnedRebalanceGroup(ASSOCIATION_ID, CLAIM_TOKEN, "INCONSISTENT", "原因",
                BUSINESS_NOW);
        verify(mapper).convergeUnclaimedRebalanceGroup(ASSOCIATION_ID, "INCONSISTENT", "原因", BUSINESS_NOW);
    }

    @Test
    @DisplayName("查询可发送通知_委托Mapper")
    void selectSendableNotices_delegatesToMapper() {
        when(mapper.selectSendableNotices()).thenReturn(List.of());

        assertTrue(dao.selectSendableNotices().isEmpty());
        verify(mapper).selectSendableNotices();
    }

    @Test
    @DisplayName("按换仓关联标识读取关联组_空标识不调用Mapper")
    void selectByRebalanceAssociationId_blankInput_returnsEmptyWithoutMapperCall() {
        assertTrue(dao.selectByRebalanceAssociationId(null).isEmpty());
        verify(mapper, never()).selectByRebalanceAssociationId(any());
    }

    @Test
    @DisplayName("存在可发送通知_委托Mapper")
    void existsSendableNotices_delegatesToMapper() {
        when(mapper.existsSendableNotices()).thenReturn(true);

        assertTrue(dao.existsSendableNotices());
        verify(mapper).existsSendableNotices();
    }

    @Test
    @DisplayName("租约恢复_非空截止时间委托Mapper并返回恢复行数")
    void recoverStaleClaims_nonNullStaleBefore_delegatesToMapper() {
        LocalDateTime staleBefore = BUSINESS_NOW.minusMinutes(5);
        when(mapper.recoverStaleClaims(staleBefore, BUSINESS_NOW)).thenReturn(1);

        assertEquals(1, dao.recoverStaleClaims(staleBefore, BUSINESS_NOW));
        verify(mapper).recoverStaleClaims(staleBefore, BUSINESS_NOW);
    }
}
