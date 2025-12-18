package pn.torn.goldeneye.torn.service.data;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogSummaryDAO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogSummaryDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogDTO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackLogRespVO;
import pn.torn.goldeneye.torn.model.torn.attack.AttackSummaryVO;

import java.util.List;

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
    private final TornApi tornApi;
    private final TornUserManager userManager;
    private final TornAttackLogDAO attackLogDao;
    private final TornAttackLogSummaryDAO summaryDao;

    /**
     * 保存攻击日志
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveAttackLog(String logId) {
        List<TornAttackLogDO> recordList = attackLogDao.lambdaQuery().eq(TornAttackLogDO::getLogId, logId).list();
        if (!CollectionUtils.isEmpty(recordList)) {
            return;
        }

        int pageNo = 0;
        int limit = 100;
        AttackLogDTO param;
        List<TornAttackLogDO> logList;

        do {
            param = new AttackLogDTO(logId, pageNo * limit);
            AttackLogRespVO resp = tornApi.sendRequest(param, AttackLogRespVO.class);
            if (resp == null || resp.getAttackLog() == null || CollectionUtils.isEmpty(resp.getAttackLog().getLog())) {
                break;
            }

            logList = resp.getAttackLog().getLog().stream()
                    .map(n -> n.convert2DO(logId, userManager))
                    .toList();
            attackLogDao.saveBatch(logList);
            if (pageNo == 0) {
                saveLogSummary(logId, resp.getAttackLog().getSummary());
            }

            pageNo++;
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (logList.size() >= limit);
    }

    /**
     * 保存日志统计
     */
    public void saveLogSummary(String logId, List<AttackSummaryVO> summaryList) {
        if (CollectionUtils.isEmpty(summaryList)) {
            return;
        }

        List<TornAttackLogSummaryDO> recordList = summaryDao.lambdaQuery()
                .eq(TornAttackLogSummaryDO::getLogId, logId)
                .list();
        if (!CollectionUtils.isEmpty(recordList)) {
            return;
        }

        List<TornAttackLogSummaryDO> dataList = summaryList.stream()
                .map(s -> s.convert2DO(logId, userManager))
                .toList();
        summaryDao.saveBatch(dataList);
    }
}