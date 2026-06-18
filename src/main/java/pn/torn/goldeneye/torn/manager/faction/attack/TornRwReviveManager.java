package pn.torn.goldeneye.torn.manager.faction.attack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.faction.revive.TornFactionRwReviveDAO;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.repository.model.faction.revive.TornFactionRwReviveDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.model.faction.revive.TornFactionReviveDTO;
import pn.torn.goldeneye.torn.model.faction.revive.TornFactionReviveRespVO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;
import pn.torn.goldeneye.torn.model.user.profile.TornUserProfileDTO;
import pn.torn.goldeneye.torn.model.user.profile.TornUserProfileVO;

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
        TornFactionReviveDTO param = new TornFactionReviveDTO(from, to);
        TornFactionReviveRespVO resp = tornApi.sendRequest(faction.getId(), param, TornFactionReviveRespVO.class);
        if (resp == null || CollectionUtils.isEmpty(resp.getReviveList())) {
            return;
        }

        List<TornFactionRwReviveDO> dataList = resp.getReviveList().stream()
                .map(revive -> revive.convert2DO(rw, faction))
                .toList();
        if (CollectionUtils.isEmpty(dataList)) {
            return;
        }

        // 批量查询已存在的复活记录
        List<TornFactionRwReviveDO> existingRevives = reviveDao.lambdaQuery()
                .eq(TornFactionRwReviveDO::getFactionId, faction.getId())
                .eq(TornFactionRwReviveDO::getRwId, rw.getId())
                .list();
        Set<String> existingKeys = existingRevives.stream()
                .map(this::buildUniqueKey)
                .collect(Collectors.toSet());
        List<TornFactionRwReviveDO> saveList = dataList.stream()
                .filter(r -> !existingKeys.contains(buildUniqueKey(r)))
                .toList();
        if (CollectionUtils.isEmpty(saveList)) {
            return;
        }

        fillLifeAndHealAmount(saveList);
        reviveDao.saveBatch(saveList);
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

            Integer currentLife = profile.getLife().getCurrent() == null ? null : profile.getLife().getCurrent();
            Integer maximumLife = profile.getLife().getMaximum() == null ? null : profile.getLife().getMaximum();
            revive.setTargetLifeCurrent(currentLife);
            revive.setTargetLifeMaximum(maximumLife);
            revive.setHealAmount(calcHealAmount(maximumLife, currentLife, revive.getSkill()));
        }
    }

    /**
     * 读取用户基本信息
     */
    private TornUserVO loadUserProfile(Long targetId) {
        try {
            return tornApi.sendRequest(targetId, new TornUserProfileDTO(targetId), TornUserVO.class);
        } catch (Exception e) {
            log.warn("查询复活目标档案失败, userId={}", targetId, e);
            return null;
        }
    }

    /**
     * 计算回复血量
     */
    private int calcHealAmount(Integer maximumLife, Integer currentLife, BigDecimal skill) {
        if (maximumLife == null || currentLife == null || skill == null) {
            return 0;
        }
        BigDecimal healAmount = BigDecimal.valueOf(maximumLife)
                .multiply(skill)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .subtract(BigDecimal.valueOf(currentLife));
        return healAmount.max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * 构建唯一Key
     */
    private String buildUniqueKey(TornFactionRwReviveDO r) {
        return r.getReviverId() + "#" + r.getTargetId() + "#" + r.getReviveTime();
    }
}
