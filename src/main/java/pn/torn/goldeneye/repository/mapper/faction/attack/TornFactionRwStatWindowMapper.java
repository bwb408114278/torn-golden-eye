package pn.torn.goldeneye.repository.mapper.faction.attack;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwStatWindowDO;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowVO;
import pn.torn.goldeneye.torn.model.faction.attack.RwUserAttackStatVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RW对冲统计窗口数据库访问层。
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@Mapper
public interface TornFactionRwStatWindowMapper extends BaseMapper<TornFactionRwStatWindowDO> {
    /**
     * 查询RW的窗口目录及双方出手总数。
     *
     * @param rwId              RW ID
     * @param factionId         己方帮派ID
     * @param opponentFactionId 对方帮派ID
     * @return 窗口目录
     */
    List<RwStatWindowVO> queryWindowCatalog(@Param("rwId") long rwId,
                                            @Param("factionId") long factionId,
                                            @Param("opponentFactionId") long opponentFactionId);

    /**
     * 查询RW已有的有效窗口。
     *
     * @param rwId RW ID
     * @return 已有窗口
     */
    List<TornFactionRwStatWindowDO> queryActiveWindows(@Param("rwId") long rwId);

    /**
     * 查询最近一个已确认且双方均有进攻的窗口。
     *
     * @param rwId              RW ID
     * @param factionId         己方帮派ID
     * @param opponentFactionId 对方帮派ID
     * @return 最近有效窗口，不存在时返回null
     */
    TornFactionRwStatWindowDO queryLatestConfirmedWindow(@Param("rwId") long rwId,
                                                         @Param("factionId") long factionId,
                                                         @Param("opponentFactionId") long opponentFactionId);

    /**
     * 查询指定窗口内双方用户出手统计。
     *
     * @param startTime         窗口开始时间
     * @param endTime           窗口结束时间
     * @param factionId         己方帮派ID
     * @param opponentFactionId 对方帮派ID
     * @return 用户出手统计
     */
    List<RwUserAttackStatVO> queryUserAttackStats(@Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime,
                                                  @Param("factionId") long factionId,
                                                  @Param("opponentFactionId") long opponentFactionId);

    /**
     * 幂等插入窗口记录。
     *
     * @param window 窗口记录
     * @return 实际插入行数
     */
    int insertIgnoreConflict(@Param("window") TornFactionRwStatWindowDO window);

    /**
     * 更新尚未确认的窗口记录。
     *
     * @param id        窗口记录ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param confirmed 是否确认
     * @return 实际更新行数
     */
    int updateUnconfirmedWindow(@Param("id") long id,
                                @Param("startTime") LocalDateTime startTime,
                                @Param("endTime") LocalDateTime endTime,
                                @Param("confirmed") boolean confirmed);
}
