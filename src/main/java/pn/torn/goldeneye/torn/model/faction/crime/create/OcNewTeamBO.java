package pn.torn.goldeneye.torn.model.faction.crime.create;

import lombok.Getter;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OC新队分析结果
 *
 * @author Bai
 * @version 0.4.0
 * @since 2026.01.05
 */
@Getter
public class OcNewTeamBO {
    /**
     * 执行中OC数量
     */
    private final int execOcCount;
    /**
     * 新队数量
     */
    private final int newOcCount;
    /**
     * 可用用户数量
     */
    private final int availableUserCount;
    /**
     * 空闲用户数量
     */
    private final int freeUserCount;
    /**
     * 即将结束的OC数量
     */
    private final int finishCount;
    /**
     * 即将停转OC数量
     */
    private final int nearByStopCount;
    /**
     * 今日停转OC数量
     */
    private int todayStopCount;
    /**
     * 匹配已有队伍人数
     */
    private int matchSuccessUserCount;
    /**
     * 匹配不到OC人员数量
     */
    private int failMatchCount;
    /**
     * 7/8级都能做人员数量
     */
    private int highAbilityUserCount;

    public OcNewTeamBO(List<TornFactionOcDO> ocList, Set<Long> availableUser, List<TornFactionOcUserDO> freeUserList,
                       List<TornFactionOcDO> stopList, List<TornFactionOcDO> finishList) {
        this.execOcCount = ocList.stream().filter(o -> o.getReadyTime() != null).toList().size();
        this.newOcCount = ocList.stream().filter(o -> o.getReadyTime() == null).toList().size();
        this.availableUserCount = availableUser.size();
        this.freeUserCount = freeUserList.stream().map(TornFactionOcUserDO::getUserId).collect(Collectors.toSet()).size();
        this.finishCount = finishList.size();
        this.nearByStopCount = stopList.size();
    }

    public void setTodayStop(Collection<TornFactionOcDO> todayStopList) {
        this.todayStopCount = todayStopList.size();
    }

    public void afterMatch(Collection<Long> matchedUserList, List<TornFactionOcDO> failMatchList) {
        this.matchSuccessUserCount = matchedUserList.size();
        this.failMatchCount = failMatchList.size();
    }

    public void setHighAbility(Set<Long> highAbilityUserSet) {
        this.highAbilityUserCount = highAbilityUserSet.size();
    }
}