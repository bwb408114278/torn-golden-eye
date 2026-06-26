package pn.torn.goldeneye.napcat.strategy.faction.crime;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.OcSuccessRankDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

/**
 * OC成功率排行表格构建器
 *
 * @author Bai
 * @version 1.2.6
 * @since 2026.06.26
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class OcSuccessRankTableBuilder {
    /**
     * 一站式：从排行列表直接生成表格图片Base64
     */
    static String buildImage(List<OcSuccessRankDO> rankingList, String title,
                             boolean isLucky, boolean isGlobal,
                             TornUserDAO userDao, LongFunction<String> factionNameResolver) {
        Set<Long> userIdSet = rankingList.stream().map(OcSuccessRankDO::getUserId).collect(Collectors.toSet());
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(userIdSet);
        return build(rankingList, title, isLucky, isGlobal, userMap, factionNameResolver);
    }

    private static String build(List<OcSuccessRankDO> rankingList, String title,
                                boolean isLucky, boolean isGlobal,
                                Map<Long, TornUserDO> userMap,
                                LongFunction<String> factionNameResolver) {

        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        int colCount = isGlobal ? 7 : 6;
        String countColumnHeader = isLucky ? "成功OC数" : "失败OC数";

        // 标题行
        tableData.add(newColList(colCount));
        tableData.getFirst().set(0, title);
        tableConfig.addMerge(0, 0, 1, colCount);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        // 表头行
        List<String> headerRow = new ArrayList<>();
        headerRow.add("Rank");
        headerRow.add("ID");
        headerRow.add("Name");
        if (isGlobal) {
            headerRow.add("帮派");
        }
        headerRow.add("参与OC数");
        headerRow.add(countColumnHeader);
        headerRow.add("成功率");
        tableData.add(headerRow);
        tableConfig.setSubTitle(1, colCount);

        // 数据行
        for (int i = 0; i < rankingList.size(); i++) {
            OcSuccessRankDO ranking = rankingList.get(i);
            TornUserDO user = userMap.get(ranking.getUserId());
            int displayCount = isLucky ? ranking.getSuccessCount()
                    : ranking.getTotalOcCount() - ranking.getSuccessCount();

            List<String> row = new ArrayList<>();
            row.add(String.valueOf(i + 1));
            row.add(ranking.getUserId().toString());
            row.add(user == null ? "未知" : user.getNickname());
            if (isGlobal) {
                row.add(factionNameResolver.apply(ranking.getFactionId()));
            }
            row.add(ranking.getTotalOcCount().toString());
            row.add(String.valueOf(displayCount));
            row.add(ranking.getSuccessRate() + "%");
            tableData.add(row);
        }

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    private static List<String> newColList(int size) {
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add("");
        }
        return list;
    }
}
