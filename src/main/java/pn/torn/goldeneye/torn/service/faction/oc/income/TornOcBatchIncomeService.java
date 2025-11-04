package pn.torn.goldeneye.torn.service.faction.oc.income;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;

import java.util.List;

/**
 * OC批量收益计算服务
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.03
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TornOcBatchIncomeService {
    private final TornOcIncomeService incomeService;
    private final TornFactionOcDAO ocDao;

    /**
     * 批量计算已完成OC的收益
     * 供定时任务调用
     */
    @Transactional(rollbackFor = Exception.class)
    public int batchCalculateIncome(long factionId) {
        // 1. 查询所有已完成但未计算收益的OC
        List<TornFactionOcDO> completedOcs = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getFactionId, factionId)
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.getCompleteStatusList())
                .isNotNull(TornFactionOcDO::getExecutedTime)
                .list();
        if (CollectionUtils.isEmpty(completedOcs)) {
            log.info("没有待计算收益的OC");
            return 0;
        }

        // 2. 筛选出未计算过收益的OC（通过查询收益表）
        List<Long> calculatedOcIds = incomeService.getCalculatedOcIds(
                completedOcs.stream().map(TornFactionOcDO::getId).toList());
        List<TornFactionOcDO> uncalculatedOcs = completedOcs.stream()
                .filter(oc -> !calculatedOcIds.contains(oc.getId()))
                .toList();
        if (CollectionUtils.isEmpty(uncalculatedOcs)) {
            log.info("所有已完成的OC都已计算收益");
            return 0;
        }

        // 3. 批量计算收益
        int successCount = 0;
        for (TornFactionOcDO oc : uncalculatedOcs) {
            try {
                incomeService.calculateAndSaveIncome(oc.getId());
                successCount++;
                log.info("成功计算OC收益: id={}, name={}, status={}",
                        oc.getId(), oc.getName(), oc.getStatus());
            } catch (Exception e) {
                log.error("计算OC收益失败: id={}, name={}", oc.getId(), oc.getName(), e);
            }
        }

        log.info("批量计算收益完成，成功{}个，失败{}个", successCount, uncalculatedOcs.size() - successCount);
        return successCount;
    }
}