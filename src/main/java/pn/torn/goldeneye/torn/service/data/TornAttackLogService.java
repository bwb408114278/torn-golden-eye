package pn.torn.goldeneye.torn.service.data;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogDTO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogRespVO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogVO;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * 攻击日志逻辑类
 *
 * @author Bai
 * @version 1.3.8
 * @since 2025.12.18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornAttackLogService {
    private static final int SAVE_BATCH_SIZE = 1000;
    private static final int LOG_ID_BATCH_SIZE = 100;
    private static final int PAGE_LIMIT = 100;

    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornAttackLogDAO attackLogDao;

    /**
     * 保存攻击日志。
     * <p>
     * 每个logId独立分页抓取完整日志流, 不做任何战斗指纹整组预过滤;
     * 多个logId返回同一共享战斗流时, 各来源结果均交给数据库六字段有效事实
     * 部分唯一索引裁决幂等。任一logId分页未完成时抛出异常, 不落库部分数据。
     *
     * @param factionId   帮派ID
     * @param logIdSet    本轮待抓取的攻击日志ID集合
     * @param userNameMap 用户ID到昵称映射, 用于补齐日志内昵称
     * @param eloMap      用户ID到ELO映射, 用于补齐攻守双方ELO
     * @throws BizException 任一logId的分页请求失败、响应为空或结构不完整时抛出
     */
    public void saveAttackLog(long factionId, Set<String> logIdSet,
                              Map<Long, String> userNameMap, Map<Long, Integer> eloMap) {
        if (CollectionUtils.isEmpty(logIdSet)) {
            return;
        }

        Set<String> existingLogIds = queryExistingLogIds(logIdSet);
        List<String> pendingLogIds = logIdSet.stream()
                .filter(logId -> !existingLogIds.contains(logId))
                .toList();

        List<List<TornAttackLogDO>> allLogList = new ArrayList<>();
        for (int start = 0; start < pendingLogIds.size(); start += LOG_ID_BATCH_SIZE) {
            int end = Math.min(start + LOG_ID_BATCH_SIZE, pendingLogIds.size());
            allLogList.addAll(fetchLogBatch(factionId, pendingLogIds.subList(start, end), userNameMap, eloMap));
        }

        saveLogData(allLogList);
    }

    /**
     * 保存日志和统计数据。
     *
     * @param allLogList 全部来源日志流, 空集合直接返回
     */
    public void saveLogData(Collection<List<TornAttackLogDO>> allLogList) {
        if (CollectionUtils.isEmpty(allLogList)) {
            return;
        }

        List<TornAttackLogDO> logList = new ArrayList<>();
        allLogList.forEach(logList::addAll);
        // 数据库六字段有效事实部分唯一索引承担最终幂等, 相同occurrence冲突行由ON CONFLICT DO NOTHING跳过
        for (int start = 0; start < logList.size(); start += SAVE_BATCH_SIZE) {
            int end = Math.min(start + SAVE_BATCH_SIZE, logList.size());
            attackLogDao.insertIgnoreConflict(logList.subList(start, end));
        }
    }

    /**
     * 查询已落库的日志ID集合, 用于跳过已完整保存过的logId
     *
     * @param logIdSet 本轮待抓取的攻击日志ID集合
     * @return 已存在于库中的日志ID集合
     */
    private Set<String> queryExistingLogIds(Set<String> logIdSet) {
        return attackLogDao.lambdaQuery()
                .in(TornAttackLogDO::getLogId, logIdSet)
                .list().stream()
                .map(TornAttackLogDO::getLogId)
                .collect(Collectors.toSet());
    }

    /**
     * 并发抓取一批日志ID的完整日志流
     *
     * @param factionId   帮派ID
     * @param logIdBatch  本批日志ID列表
     * @param userNameMap 用户ID到昵称映射
     * @param eloMap      用户ID到ELO映射
     * @return 本批全部日志流, 任一logId失败时异常直接传播
     */
    private List<List<TornAttackLogDO>> fetchLogBatch(long factionId, List<String> logIdBatch,
                                                      Map<Long, String> userNameMap, Map<Long, Integer> eloMap) {
        List<CompletableFuture<List<List<TornAttackLogDO>>>> futureList = logIdBatch.stream()
                .map(logId -> CompletableFuture.supplyAsync(
                        () -> parseLog(factionId, logId, userNameMap, eloMap), virtualThreadExecutor))
                .toList();

        return futureList.stream()
                .map(this::joinFuture)
                .flatMap(List::stream)
                .toList();
    }

    /**
     * 等待异步任务完成, 解开CompletionException包装使业务异常原样传播
     *
     * @param future 待等待的异步任务
     * @param <T>    任务结果类型
     * @return 任务结果
     */
    private <T> T joinFuture(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    /**
     * 分页抓取并转换单个logId的完整攻击日志流。
     * <p>
     * 当前页声明next时必须取到后续页; 后续页失败、为空或结构不完整时抛出异常,
     * 不得把前序部分页作为成功结果返回。
     *
     * @param factionId   帮派ID
     * @param logId       日志ID
     * @param userNameMap 用户ID到昵称映射
     * @param eloMap      用户ID到ELO映射
     * @return 该logId按页组织的完整日志流
     * @throws BizException 任一页请求失败、响应为空或结构不完整时抛出
     */
    private List<List<TornAttackLogDO>> parseLog(long factionId, String logId,
                                                 Map<Long, String> userNameMap, Map<Long, Integer> eloMap) {
        List<List<TornAttackLogDO>> resultList = new ArrayList<>();
        Map<AttackLogFactKey, Integer> occurrenceMap = new HashMap<>();
        int offset = 0;
        while (true) {
            AttackLogRespVO resp = tornApi.sendRequest(factionId, new AttackLogDTO(logId, offset), AttackLogRespVO.class);
            List<AttackLogVO> pageLogs = extractPageLog(logId, offset, resp);
            resultList.add(convertPageWithOccurrence(logId, pageLogs, userNameMap, eloMap, occurrenceMap));
            if (!hasNextPage(logId, offset, resp)) {
                return resultList;
            }
            offset += PAGE_LIMIT;
        }
    }

    /**
     * 校验并提取当前页日志列表
     *
     * @param logId  日志ID
     * @param offset 当前页offset
     * @param resp   API响应
     * @return 当前页日志列表
     * @throws BizException 响应为空、结构不完整或日志列表为空时抛出
     */
    private List<AttackLogVO> extractPageLog(String logId, int offset, AttackLogRespVO resp) {
        if (resp == null || resp.getAttackLog() == null) {
            throw pageIncompleteException(logId, offset, "响应为空或结构不完整");
        }
        if (CollectionUtils.isEmpty(resp.getAttackLog().getLog())) {
            throw pageIncompleteException(logId, offset, "日志列表为空");
        }
        return resp.getAttackLog().getLog();
    }

    /**
     * 判断当前页是否声明了下一页链接
     *
     * @param logId  日志ID
     * @param offset 当前页offset
     * @param resp   API响应
     * @return 存在下一页链接时返回true
     * @throws BizException 元数据链接缺失时抛出
     */
    private boolean hasNextPage(String logId, int offset, AttackLogRespVO resp) {
        if (resp.getMetaData() == null || resp.getMetaData().getLinks() == null) {
            throw pageIncompleteException(logId, offset, "元数据链接缺失");
        }
        return StringUtils.hasText(resp.getMetaData().getLinks().getNext());
    }

    /**
     * 按API返回顺序转换当前页并分配来源流内occurrence
     *
     * @param logId         日志ID
     * @param pageLogs      当前页日志列表
     * @param userNameMap   用户ID到昵称映射
     * @param eloMap        用户ID到ELO映射
     * @param occurrenceMap 该logId跨页共享的出现序号计数表
     * @return 当前页转换后的日志列表
     */
    private List<TornAttackLogDO> convertPageWithOccurrence(String logId, List<AttackLogVO> pageLogs,
                                                            Map<Long, String> userNameMap, Map<Long, Integer> eloMap,
                                                            Map<AttackLogFactKey, Integer> occurrenceMap) {
        return pageLogs.stream()
                .map(logVO -> assignOccurrence(logVO.convert2DO(logId, userNameMap, eloMap), occurrenceMap))
                .toList();
    }

    /**
     * 按事实五字段在来源流计数表内递增并写入出现序号
     *
     * @param logDO         已转换的日志对象
     * @param occurrenceMap 该logId跨页共享的出现序号计数表
     * @return 写入出现序号后的日志对象
     */
    private TornAttackLogDO assignOccurrence(TornAttackLogDO logDO, Map<AttackLogFactKey, Integer> occurrenceMap) {
        AttackLogFactKey factKey = new AttackLogFactKey(logDO.getAttackerId(), logDO.getDefenderId(),
                logDO.getLogTime(), logDO.getLogText(), logDO.getLogAction());
        logDO.setSourceOccurrence(occurrenceMap.merge(factKey, 1, Integer::sum));
        return logDO;
    }

    /**
     * 构造分页未完成业务异常, 消息仅含logId、offset与失败类别, 不含敏感信息
     *
     * @param logId        日志ID
     * @param offset       当前页offset
     * @param failCategory 失败类别
     * @return 业务异常
     */
    private BizException pageIncompleteException(String logId, int offset, String failCategory) {
        return new BizException("攻击日志分页未完成, logId=" + logId + ", offset=" + offset
                + ", 失败类别=" + failCategory);
    }

    /**
     * occurrence计数键: 单个logId响应流内的事实五字段, 仅用于来源流内出现序号计数
     *
     * @param attackerId 攻方ID
     * @param defenderId 守方ID
     * @param logTime    日志时间
     * @param logText    日志文本
     * @param logAction  发生动作
     */
    private record AttackLogFactKey(
            Long attackerId,
            Long defenderId,
            LocalDateTime logTime,
            String logText,
            String logAction) {
    }
}
