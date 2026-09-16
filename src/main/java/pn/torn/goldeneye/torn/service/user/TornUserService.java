package pn.torn.goldeneye.torn.service.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.user.TornUserDTO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;
import pn.torn.goldeneye.torn.model.user.profile.TornUserProfileDTO;
import pn.torn.goldeneye.torn.model.user.profile.TornUserProfileVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Torn用户通用逻辑
 *
 * @author Bai
 * @version 1.6.3
 * @since 2025.08.27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornUserService {
    private final TornApi tornApi;
    private final TornUserDAO userDao;
    private final ThreadPoolTaskExecutor virtualThreadExecutor;

    /**
     * 更新用户信息
     */
    public void updateUserData(TornApiKeyDO key) {
        TornUserVO user = tornApi.sendRequest(new TornUserDTO(), key, TornUserVO.class);
        if (user == null) {
            return;
        }

        TornUserDO userData = user.getProfile().convert2DO();
        TornUserDO oldData = userDao.getById(userData.getId());
        if (oldData != null) {
            userDao.updateById(userData);
        } else {
            userDao.save(userData);
        }
    }

    /**
     * 补齐指定用户的注册时间。
     *
     * <p>只处理本地注册时间仍为空的用户；单个用户失败只记录日志，不阻断其余用户。</p>
     *
     * @param userIdList 候选Torn用户ID
     */
    public void backfillRegisterTime(Collection<Long> userIdList) {
        if (CollectionUtils.isEmpty(userIdList)) {
            return;
        }

        List<Long> missingIdList = userDao.queryIdListWithoutRegisterTime(userIdList);
        if (missingIdList.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> futureList = missingIdList.stream()
                .map(userId -> CompletableFuture.runAsync(() -> refreshRegisterTime(userId), virtualThreadExecutor))
                .toList();
        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0])).join();
        log.info("注册时间补齐完成, candidateCount={}, missingCount={}", userIdList.size(), missingIdList.size());
    }

    /**
     * 读取单个用户的注册时间，并只在本地仍为空时回写。
     *
     * @param userId Torn用户ID
     */
    private void refreshRegisterTime(long userId) {
        try {
            TornUserVO resp = tornApi.sendRequest(new TornUserProfileDTO(userId), TornUserVO.class);
            TornUserProfileVO profile = resp == null ? null : resp.getProfile();
            LocalDateTime registerTime = profile == null ? null
                    : DateTimeUtils.convertToDateTime(profile.getSignedUp());
            if (registerTime == null) {
                return;
            }

            userDao.updateRegisterTimeIfAbsent(userId, registerTime);
        } catch (Exception e) {
            log.warn("注册时间补齐失败, userId={}", userId, e);
        }
    }
}
