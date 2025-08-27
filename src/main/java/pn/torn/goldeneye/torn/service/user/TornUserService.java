package pn.torn.goldeneye.torn.service.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.user.TornUserDTO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;

/**
 * Torn用户通用逻辑
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.27
 */
@Service
@RequiredArgsConstructor
public class TornUserService {
    private final TornApi tornApi;
    private final TornUserDAO userDao;

    /**
     * 更新用户信息
     */
    public void updateUserData(TornApiKeyDO key) {
        TornUserVO user = tornApi.sendRequest(new TornUserDTO(), key, TornUserVO.class);
        if (user == null) {
            return;
        }

        TornUserDO userData = user.convert2DO();
        TornUserDO oldData = userDao.getById(userData.getId());
        if (oldData != null) {
            userDao.updateById(userData);
        } else {
            userDao.save(userData);
        }
    }
}