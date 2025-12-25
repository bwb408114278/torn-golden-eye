package pn.torn.goldeneye.torn.service.data;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogDTO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogRespVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.util.*;
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

    /**
     * 保存攻击日志
     */
    public void saveAttackLog(long factionId, Set<String> logIdSet, Map<Long, String> userNameMap) {
        if (CollectionUtils.isEmpty(logIdSet)) {
            return;
        }

        List<TornAttackLogDO> recordList = attackLogDao.lambdaQuery()
                .in(TornAttackLogDO::getLogId, logIdSet)
                .list();
        List<CompletableFuture<List<List<TornAttackLogDO>>>> futureList = logIdSet.stream()
                .map(logId -> CompletableFuture.supplyAsync(
                        () -> parseLog(factionId, logId, userNameMap, recordList),
                        virtualThreadExecutor))
                .toList();
        List<List<TornAttackLogDO>> allLogList = futureList.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .toList();
        Collection<List<TornAttackLogDO>> logList = filterRepeatLog(allLogList);
        saveLogData(logList);
    }

    /**
     * 转换Log数据
     */
    private List<List<TornAttackLogDO>> parseLog(long factionId, String logId, Map<Long, String> userNameMap,
                                                 List<TornAttackLogDO> recordList) {
        boolean isExists = recordList.stream().anyMatch(l -> l.getLogId().equals(logId));
        if (isExists) {
            return List.of();
        }

        int pageNo = 0;
        int limit = 100;
        AttackLogDTO param;
        AttackLogRespVO resp;
        List<List<TornAttackLogDO>> resultList = new ArrayList<>();

        do {
            param = new AttackLogDTO(logId, pageNo * limit);
            TornApiKeyDO key = apiKeyConfig.getFactionKey(factionId, false);
            if (key != null) {
                resp = tornApi.sendRequest(param, key, AttackLogRespVO.class);
            } else {
                resp = tornApi.sendRequest(param, AttackLogRespVO.class);
            }

            if (resp == null || resp.getAttackLog() == null || CollectionUtils.isEmpty(resp.getAttackLog().getLog())) {
                break;
            }

            resultList.add(resp.getAttackLog().getLog().stream()
                    .map(n -> n.convert2DO(logId, userNameMap))
                    .toList());
            pageNo++;
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (resp.getAttackLog().getLog().size() >= limit);

        return resultList;
    }

    /**
     * 保存日志和统计数据
     */
    public void saveLogData(Collection<List<TornAttackLogDO>> allLogList) {
        if (CollectionUtils.isEmpty(allLogList)) {
            return;
        }

        List<TornAttackLogDO> logList = new ArrayList<>();
        allLogList.forEach(logList::addAll);
        attackLogDao.saveBatch(logList);
    }

    /**
     * 过滤重复日志
     */
    private Collection<List<TornAttackLogDO>> filterRepeatLog(List<List<TornAttackLogDO>> allLogList) {
        if (CollectionUtils.isEmpty(allLogList)) {
            return List.of();
        }

        Map<String, List<TornAttackLogDO>> logMap = new HashMap<>();
        for (List<TornAttackLogDO> logList : allLogList) {
            TornAttackLogDO initAttackLog = logList.stream()
                    .filter(l -> l.getLogText().contains("initiated an attack against"))
                    .findAny().orElse(null);
            if (initAttackLog == null) {
                continue;
            }

            String key = initAttackLog.getAttackerId() + "#" +
                    initAttackLog.getDefenderId() + "#" +
                    DateTimeUtils.convertToString(initAttackLog.getLogTime());
            logMap.putIfAbsent(key, logList);
        }

        return logMap.values();
    }
}