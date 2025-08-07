package pn.torn.goldeneye.repository.dao.user;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.user.TornUserMapper;
import pn.torn.goldeneye.repository.model.user.TornUserDO;

import java.util.*;

/**
 * Torn User持久层类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Repository
public class TornUserDAO extends ServiceImpl<TornUserMapper, TornUserDO> {
    /**
     * 获取昵称Map
     *
     * @param nicknameList 昵称列表
     * @return Key为昵称, Value为用户ID
     */
    public Map<String, Long> getNicknameMap(Collection<String> nicknameList) {
        Set<String> nickNameSet;
        if (nicknameList instanceof Set<String> set) {
            nickNameSet = set;
        } else {
            nickNameSet = new HashSet<>(nicknameList);
        }

        List<TornUserDO> userList = lambdaQuery().in(TornUserDO::getNickname, nickNameSet).list();
        Map<String, Long> resultMap = HashMap.newHashMap(userList.size());
        userList.forEach(u -> resultMap.put(u.getNickname(), u.getId()));
        return resultMap;
    }
}