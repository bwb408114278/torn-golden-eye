package pn.torn.goldeneye.repository.mapper.torn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 攻击日志冲突安全批量写入 Mapper 真实 PostgreSQL 测试
 * <p>
 * 验证 {@code insertIgnoreConflict} 的幂等语义：有效攻击日志事实以
 * (attacker_id, defender_id, log_time, log_text, log_action)五字段为唯一键
 * (部分唯一索引, 仅约束 deleted=0 行), 同批次/跨批次重放只保留一条有效事实,
 * 重复行仅跳过自身且不影响批次内后续新事实写入; 自定义 XML 批量 INSERT 由 DAO
 * 以雪花 ID 补齐主键, 落库主键非空。
 * <p>
 * 数据使用 2099 严格未来时间与测试专用攻/守方 ID 命名空间隔离真实 RW 数据,
 * 类级事务以 {@code @Rollback} 回滚, 测试库零残留。
 *
 * @author Bai
 * @version 1.3.5
 * @since 2026.08.20
 */
@SpringBootTest
@DisplayName("攻击日志冲突安全批量写入Mapper真实PostgreSQL测试")
@Transactional
@Rollback
class TornAttackLogMapperTest {

    @Autowired
    private TornAttackLogDAO attackLogDao;

    /**
     * 隔离测试日志时间（2099严格未来时间命名空间，远离生产数据）
     */
    private static final LocalDateTime LOG_TIME = LocalDateTime.of(2099, 10, 1, 0, 0);
    /**
     * 测试专用攻方ID（远离真实Torn用户ID段）
     */
    private static final long ATTACKER_ID = 99800001L;
    /**
     * 测试专用守方ID
     */
    private static final long DEFENDER_ID = 99800002L;

    @Test
    @DisplayName("真实PG_新→重复→新批次只插入两条新事实, 同事实重放全跳过")
    void insertIgnoreConflict_newDuplicateNew_skipsOnlyDuplicateRow() {
        List<TornAttackLogDO> logList = List.of(
                attackLog("rw-t-a1", "hit", "A hit B"),
                attackLog("rw-t-a2", "hit", "A hit B"),
                attackLog("rw-t-b1", "missed", "A missed B"));

        int inserted = attackLogDao.insertIgnoreConflict(logList);
        assertEquals(2, inserted, "重复事实仅跳过自身, 不得影响批次内后续新事实写入");

        List<TornAttackLogDO> saved = queryByLogIds("rw-t-a1", "rw-t-a2", "rw-t-b1");
        assertEquals(2, saved.size(), "A的重复副本(不同log_id)不得单独落库");
        assertTrue(saved.stream().allMatch(row -> row.getId() != null && row.getId() > 0),
                "XML批量INSERT主键由DAO雪花补齐, 落库主键必须非空且大于0");
        assertEquals(Set.of("A hit B", "A missed B"),
                saved.stream().map(TornAttackLogDO::getLogText).collect(Collectors.toSet()),
                "隔离范围只允许存在事实A与事实B两行");

        // 重放使用新构造对象: 生产重放每次都从API响应重新构建DO(主键为空由DAO补齐),
        // 复用已落库对象会携带已存在主键, 造成主键冲突而非业务事实冲突, 不属于生产行为
        int replayInserted = attackLogDao.insertIgnoreConflict(List.of(
                attackLog("rw-t-a3", "hit", "A hit B"),
                attackLog("rw-t-b2", "missed", "A missed B")));
        assertEquals(0, replayInserted, "同事实跨批次重放必须被ON CONFLICT DO NOTHING全跳过");
        assertEquals(2, queryByLogIds("rw-t-a1", "rw-t-a2", "rw-t-a3", "rw-t-b1", "rw-t-b2").size(),
                "重放后有效事实行数必须保持2行");
    }

    @Test
    @DisplayName("真实PG_逻辑删除行不占有效事实唯一性, 同事实有效行仍可写入")
    void insertIgnoreConflict_deletedRowNotConflict_activeFactWritable() {
        TornAttackLogDO deletedRow = attackLog("rw-d-1", "hit", "deleted fact");
        deletedRow.setDeleted(1);
        assertTrue(attackLogDao.save(deletedRow), "预置deleted=1同事实行必须可插入, 不触碰部分唯一索引");

        int inserted = attackLogDao.insertIgnoreConflict(List.of(attackLog("rw-d-2", "hit", "deleted fact")));
        assertEquals(1, inserted, "唯一索引谓词仅约束deleted=0, 同事实有效行必须可写入");

        int replayInserted = attackLogDao.insertIgnoreConflict(List.of(attackLog("rw-d-3", "hit", "deleted fact")));
        assertEquals(0, replayInserted, "有效事实已存在时同事实重放必须全跳过");
        assertEquals(1, queryByLogIds("rw-d-2", "rw-d-3").size(), "逻辑删除行不占用有效事实唯一性");
    }

    /**
     * 构造测试攻击日志
     *
     * @param logId     日志ID
     * @param logAction 发生动作
     * @param logText   日志文本
     * @return 待写入的攻击日志对象, 主键保持为空由DAO补齐
     */
    private TornAttackLogDO attackLog(String logId, String logAction, String logText) {
        TornAttackLogDO logDO = new TornAttackLogDO();
        logDO.setLogId(logId);
        logDO.setLogTime(LOG_TIME);
        logDO.setLogText(logText);
        logDO.setLogAction(logAction);
        logDO.setLogIcon("icon");
        logDO.setAttackerId(ATTACKER_ID);
        logDO.setAttackerName("rw测试攻方");
        logDO.setAttackerItemId(0L);
        logDO.setAttackerItemName("");
        logDO.setDefenderId(DEFENDER_ID);
        logDO.setDefenderName("rw测试守方");
        return logDO;
    }

    /**
     * 按日志ID查询隔离范围内的落库行（MyBatis-Plus逻辑删除自动过滤deleted=0）
     *
     * @param logIds 日志ID列表
     * @return 有效落库行
     */
    private List<TornAttackLogDO> queryByLogIds(String... logIds) {
        return attackLogDao.lambdaQuery()
                .in(TornAttackLogDO::getLogId, List.of(logIds))
                .list();
    }
}
