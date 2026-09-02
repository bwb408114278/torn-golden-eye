package pn.torn.goldeneye.torn.manager.faction.crime.msg;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendTableBO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.service.faction.oc.image.OcTableDocumentAssembler;
import pn.torn.goldeneye.utils.image.document.TableDocument;
import pn.torn.goldeneye.utils.image.render.TableImageRenderer;

import java.time.LocalDateTime;
import java.util.*;

/**
 * OC消息公共逻辑。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcMsgManager {
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final TornUserDAO userDao;
    private final SysSettingDAO settingDao;
    private final OcTableDocumentAssembler documentAssembler;
    private final TableImageRenderer imageRenderer;

    /**
     * 构建当前OC表格图片。
     *
     * @param title  标题
     * @param ocList OC列表
     * @return 表格图片Base64
     */
    public String buildOcTable(String title, List<TornFactionOcDO> ocList) {
        LocalDateTime now = LocalDateTime.now();
        TableDocument document = buildOcTableData(title, ocList, now);
        return imageRenderer.render(document);
    }

    /**
     * 使用固定当前时间组装当前OC表格，供同包测试验证时间边界。
     *
     * @param title  标题
     * @param ocList OC列表
     * @param now    图片构建时的当前时间
     * @return 表格文档
     */
    TableDocument buildOcTableData(String title, List<TornFactionOcDO> ocList, LocalDateTime now) {
        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(ocList);
        Map<Long, List<TornFactionOcSlotDO>> slotByOcId = groupSlotsByOcId(slotList);
        Map<Long, TornUserDO> userMap = queryUserMap(slotList);
        String footer = "上次更新时间: " + settingDao.querySettingValue(SettingConstants.KEY_OC_LOAD);
        return documentAssembler.assemble(title, ocList, slotByOcId, userMap, footer, now);
    }

    /**
     * 构建推荐表格图片，推荐和分配入口共用此方法。
     *
     * @param title     标题
     * @param factionId 帮派ID
     * @param map       用户到推荐结果的映射
     * @return 表格图片Base64
     */
    public String buildRecommendTable(String title, long factionId, Map<TornUserDO, OcRecommendationVO> map) {
        return buildRecommendTable(title, factionId, map.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> new OcRecommendTableBO(entry.getKey(), entry.getValue()))
                .toList());
    }

    /**
     * 构建推荐或分配表格图片。
     *
     * @param title         标题
     * @param factionId     帮派ID
     * @param recommendList 推荐列表
     * @return 表格图片Base64
     */
    public String buildRecommendTable(String title, long factionId, List<OcRecommendTableBO> recommendList) {
        LocalDateTime now = LocalDateTime.now();
        return imageRenderer.render(buildRecommendTableData(title, factionId, recommendList, now));
    }

    /**
     * 使用固定当前时间组装推荐或分配表格，供同包测试验证文档结构。
     *
     * @param title         标题
     * @param factionId     帮派ID
     * @param recommendList 推荐列表
     * @param now           图片构建时的当前时间
     * @return 表格文档
     */
    TableDocument buildRecommendTableData(String title, long factionId,
                                          List<OcRecommendTableBO> recommendList, LocalDateTime now) {
        List<Long> ocIdList = recommendList.stream()
                .map(entry -> entry.recommend().getOcId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<TornFactionOcDO> ocList = ocDao.queryListByIdList(factionId, ocIdList);
        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(ocList);
        Map<Long, TornFactionOcDO> ocById = new HashMap<>();
        ocList.forEach(oc -> ocById.put(oc.getId(), oc));
        Map<Long, List<TornFactionOcSlotDO>> slotByOcId = groupSlotsByOcId(slotList);
        Map<Long, TornUserDO> userMap = queryUserMap(slotList);
        List<OcTableDocumentAssembler.Block> blocks = new ArrayList<>();
        for (OcRecommendTableBO entry : recommendList) {
            OcRecommendationVO recommendation = entry.recommend();
            TornFactionOcDO oc = ocById.get(recommendation.getOcId());
            if (oc != null) {
                blocks.add(new OcTableDocumentAssembler.Block(oc,
                        slotByOcId.getOrDefault(oc.getId(), List.of()),
                        entry.buildSummaryText(), recommendation.getRecommendedPosition(),
                        recommendation.getRecommendScore(), recommendation.getReason()));
            }
        }
        return documentAssembler.assemble(title, blocks, userMap, now);
    }

    private Map<Long, TornUserDO> queryUserMap(Collection<TornFactionOcSlotDO> slotList) {
        List<Long> userIdList = slotList.stream()
                .map(TornFactionOcSlotDO::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return userDao.queryUserMap(userIdList);
    }

    private Map<Long, List<TornFactionOcSlotDO>> groupSlotsByOcId(List<TornFactionOcSlotDO> slotList) {
        Map<Long, List<TornFactionOcSlotDO>> result = new LinkedHashMap<>();
        for (TornFactionOcSlotDO slot : slotList) {
            result.computeIfAbsent(slot.getOcId(), ignored -> new ArrayList<>()).add(slot);
        }
        return result;
    }
}
