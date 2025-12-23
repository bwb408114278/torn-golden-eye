package pn.torn.goldeneye.torn.service.data;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogSummaryDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogSummaryDO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogDTO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogRespVO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 攻击日志逻辑类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornAttackLogService {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final TornApi tornApi;
    private final TornApiKeyConfig apiKeyConfig;
    private final TornAttackLogDAO attackLogDao;
    private final TornAttackLogSummaryDAO summaryDao;

    /**
     * 保存攻击日志
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveAttackLog(long factionId, List<String> logIdList, Map<Long, String> userNameMap) {
        if (CollectionUtils.isEmpty(logIdList)) {
            return;
        }

        List<TornAttackLogDO> recordList = attackLogDao.lambdaQuery()
                .in(TornAttackLogDO::getLogId, logIdList)
                .list();
        Map<List<TornAttackLogDO>, List<TornAttackLogSummaryDO>> logMap = HashMap.newHashMap(logIdList.size());
        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        for (String logId : logIdList) {
            futureList.add(CompletableFuture.runAsync(() ->
                            logMap.putAll(parseLog(factionId, logId, userNameMap, recordList)),
                    virtualThreadExecutor));
        }

        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
        saveLogData(logMap);
    }

    /**
     * 转换Log数据
     */
    private Map<List<TornAttackLogDO>, List<TornAttackLogSummaryDO>> parseLog(long factionId, String logId,
                                                                              Map<Long, String> userNameMap,
                                                                              List<TornAttackLogDO> recordList) {
        boolean isExists = recordList.stream().anyMatch(l -> l.getLogId().equals(logId));
        if (isExists) {
            return Map.of();
        }

        int pageNo = 0;
        int limit = 100;
        AttackLogDTO param;
        List<TornAttackLogDO> logList = new ArrayList<>();
        List<TornAttackLogSummaryDO> summaryList = new ArrayList<>();

        do {
            param = new AttackLogDTO(logId, pageNo * limit);
            AttackLogRespVO resp;
            TornApiKeyDO key = apiKeyConfig.getFactionKey(factionId, false);
            if (key != null) {
                resp = tornApi.sendRequest(param, key, AttackLogRespVO.class);
            } else {
                resp = tornApi.sendRequest(param, AttackLogRespVO.class);
            }

            if (resp == null || resp.getAttackLog() == null || CollectionUtils.isEmpty(resp.getAttackLog().getLog())) {
                break;
            }

            logList.addAll(resp.getAttackLog().getLog().stream()
                    .map(n -> n.convert2DO(logId, userNameMap))
                    .toList());
            summaryList.addAll(resp.getAttackLog().getSummary().stream()
                    .map(n -> n.convert2DO(logId, userNameMap))
                    .toList());
            pageNo++;
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (logList.size() >= limit);

        return Map.of(logList, summaryList);
    }

    /**
     * 保存日志和统计数据
     */
    public void saveLogData(Map<List<TornAttackLogDO>, List<TornAttackLogSummaryDO>> logMap) {
        if (CollectionUtils.isEmpty(logMap)) {
            return;
        }

        List<TornAttackLogDO> logList = new ArrayList<>();
        List<TornAttackLogSummaryDO> summaryList = new ArrayList<>();
        logMap.forEach((k, v) -> {
            logList.addAll(k);
            summaryList.addAll(v);
        });

        attackLogDao.saveBatch(logList);
        summaryDao.saveBatch(summaryList);
    }
}