package pn.torn.goldeneye.repository.dao.faction.attack;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.faction.attack.TornFactionRwStatWindowMapper;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwStatWindowDO;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowVO;
import pn.torn.goldeneye.torn.model.faction.attack.RwUserAttackStatVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RW对冲统计窗口持久层封装。
 *
 * @author Bai
 * @version 1.4.5
 * @since 2026.08.24
 */
@Repository
public class TornFactionRwStatWindowDAO extends ServiceImpl<TornFactionRwStatWindowMapper,
        TornFactionRwStatWindowDO> {
    /**
     * 查询RW窗口目录。
     *
     * @param rwId              RW ID
     * @param factionId         己方帮派ID
     * @param opponentFactionId 对方帮派ID
     * @return 窗口目录
     */
    public List<RwStatWindowVO> queryWindowCatalog(long rwId, long factionId, long opponentFactionId) {
        return baseMapper.queryWindowCatalog(rwId, factionId, opponentFactionId);
    }

    /**
     * 查询RW已有的有效窗口。
     *
     * @param rwId RW ID
     * @return 已有窗口
     */
    public List<TornFactionRwStatWindowDO> queryActiveWindows(long rwId) {
        return baseMapper.queryActiveWindows(rwId);
    }

    /**
     * 查询最近已确认且双方均有进攻的窗口。
     *
     * @param rwId              RW ID
     * @param factionId         己方帮派ID
     * @param opponentFactionId 对方帮派ID
     * @return 最近有效窗口，不存在时返回null
     */
    public TornFactionRwStatWindowDO queryLatestConfirmedWindow(long rwId, long factionId,
                                                                long opponentFactionId) {
        return baseMapper.queryLatestConfirmedWindow(rwId, factionId, opponentFactionId);
    }

    /**
     * 查询窗口内的双方用户出手统计。
     *
     * @param startTime         窗口开始时间
     * @param endTime           窗口结束时间
     * @param factionId         己方帮派ID
     * @param opponentFactionId 对方帮派ID
     * @return 用户出手统计
     */
    public List<RwUserAttackStatVO> queryUserAttackStats(LocalDateTime startTime, LocalDateTime endTime,
                                                         long factionId, long opponentFactionId) {
        return baseMapper.queryUserAttackStats(startTime, endTime, factionId, opponentFactionId);
    }

    /**
     * 按RW聚合全部窗口内的双方用户出手次数。
     *
     * @param rwId              RW ID
     * @param factionId         己方帮派ID
     * @param opponentFactionId 对方帮派ID
     * @return 用户出手统计
     */
    public List<RwUserAttackStatVO> queryUserAttackStatsByRw(long rwId, long factionId, long opponentFactionId) {
        return baseMapper.queryUserAttackStatsByRw(rwId, factionId, opponentFactionId);
    }

    /**
     * 插入窗口记录，冲突时跳过当前记录。
     *
     * @param window 窗口记录
     * @return 实际插入行数
     */
    public int insertIgnoreConflict(TornFactionRwStatWindowDO window) {
        if (window.getId() == null) {
            window.setId(IdWorker.getId());
        }
        return baseMapper.insertIgnoreConflict(window);
    }

    /**
     * 更新未确认窗口。
     *
     * @param window    窗口记录
     * @param confirmed 是否确认
     * @return 实际更新行数
     */
    public int updateUnconfirmedWindow(TornFactionRwStatWindowDO window, boolean confirmed) {
        return baseMapper.updateUnconfirmedWindow(window.getId(), window.getStartTime(), window.getEndTime(), confirmed);
    }
}
