package pn.torn.goldeneye.torn.manager.faction.attack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.faction.revive.TornFactionRwReviveDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwReviveDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.model.faction.revive.TornFactionReviveDTO;
import pn.torn.goldeneye.torn.model.faction.revive.TornFactionReviveRespVO;
import pn.torn.goldeneye.torn.model.faction.revive.TornFactionReviveVO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;
import pn.torn.goldeneye.torn.model.user.profile.TornUserProfileDTO;
import pn.torn.goldeneye.torn.model.user.profile.TornUserProfileVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Torn RW复活数据公共逻辑层
 *
 * @author Bai
 * @version 1.2.3
 * @since 2026.06.17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornRwReviveManager {
    private final TornApi tornApi;
    private final TornFactionRwReviveDAO reviveDao;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;

    /**
     * 爬取复活数据
     */
    public void spiderReviveData(TornSettingFactionDO faction, TornFactionRwDO rw,
                                 LocalDateTime from, LocalDateTime to) {
        int limit = 100;
        TornFactionReviveDTO param;
        LocalDateTime queryFrom = from;
        TornFactionReviveRespVO resp;
        List<TornFactionRwReviveDO> reviveList;

        do {
            param = new TornFactionReviveDTO(queryFrom, to, limit);
            resp = tornApi.sendRequest(faction.getId(), param, TornFactionReviveRespVO.class);
            if (resp == null || CollectionUtils.isEmpty(resp.getReviveList())) {
                break;
            }

            reviveList = parseReviveList(rw, resp);
            if (!CollectionUtils.isEmpty(reviveList)) {
                fillLifeAndHealAmount(reviveList);
                reviveDao.saveBatch(reviveList);
            }

            queryFrom = DateTimeUtils.convertToDateTime(resp.getReviveList().getLast().getTimestamp());
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (resp.getReviveList().size() >= limit);
    }

    /**
     * 转换复活数据列表
     */
    private List<TornFactionRwReviveDO> parseReviveList(TornFactionRwDO rw, TornFactionReviveRespVO resp) {
        Set<Long> idSet = resp.getReviveList().stream().map(TornFactionReviveVO::getId).collect(Collectors.toSet());
        Set<Long> existIdSet = reviveDao.lambdaQuery()
                .in(TornFactionRwReviveDO::getId, idSet)
                .list().stream()
                .map(TornFactionRwReviveDO::getId)
                .collect(Collectors.toSet());
        return resp.getReviveList().stream()
                .distinct()
                .filter(r -> !existIdSet.contains(r.getId()))
                .map(revive -> revive.convert2DO(rw))
                .toList();
    }

    /**
     * 填充回复血量
     */
    private void fillLifeAndHealAmount(List<TornFactionRwReviveDO> reviveList) {
        Set<Long> targetIdList = reviveList.stream()
                .map(TornFactionRwReviveDO::getTargetId)
                .collect(Collectors.toSet());
        List<CompletableFuture<TornUserVO>> futureList = new ArrayList<>(targetIdList.size());
        for (Long targetId : targetIdList) {
            futureList.add(CompletableFuture.supplyAsync(() -> loadUserProfile(targetId), virtualThreadExecutor));
        }
        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();

        Map<Long, TornUserProfileVO> profileMap = futureList.stream()
                .map(CompletableFuture::join)
                .map(TornUserVO::getProfile)
                .collect(Collectors.toMap(TornUserProfileVO::getId, Function.identity(), (first, second) -> second));

        for (TornFactionRwReviveDO revive : reviveList) {
            TornUserProfileVO profile = profileMap.get(revive.getTargetId());
            if (profile == null || profile.getLife() == null) {
                continue;
            }

            Integer maximumLife = profile.getLife().getMaximum() == null ? null : profile.getLife().getMaximum();
            revive.setTargetLifeMaximum(maximumLife);
            revive.setHealAmount(calcHealAmount(maximumLife, revive.getSkill()));
        }
    }

    /**
     * 读取用户基本信息
     */
    private TornUserVO loadUserProfile(Long targetId) {
        try {
            return tornApi.sendRequest(new TornUserProfileDTO(targetId), TornUserVO.class);
        } catch (Exception e) {
            log.warn("查询复活目标档案失败, userId={}", targetId, e);
            return null;
        }
    }

    /**
     * 计算回复血量
     */
    private int calcHealAmount(Integer maximumLife, BigDecimal skill) {
        if (maximumLife == null || skill == null) {
            return 0;
        }

        BigDecimal healAmount = BigDecimal.valueOf(maximumLife)
                .multiply(skill)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return healAmount.max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
