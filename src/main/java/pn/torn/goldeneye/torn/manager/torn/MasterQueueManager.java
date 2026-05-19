package pn.torn.goldeneye.torn.manager.torn;

import com.lark.oapi.service.bitable.v1.model.AppTableRecord;
import com.lark.oapi.service.bitable.v1.model.SearchAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.SearchAppTableRecordReqBody;
import com.lark.oapi.service.bitable.v1.model.SearchAppTableRecordRespBody;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.cache.DataCacheManager;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteBitTableProperty;
import pn.torn.goldeneye.configuration.property.larksuite.LarkSuiteProperty;
import pn.torn.goldeneye.constants.torn.CacheConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.torn.model.user.master.MasterQueueVO;
import pn.torn.goldeneye.utils.larksuite.LarkSuiteUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 师父排队逻辑层
 *
 * @author Bai
 * @version 1.1.2
 * @since 2026.05.18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MasterQueueManager implements DataCacheManager {
    private final LarkSuiteApi larkSuiteApi;
    private final LarkSuiteProperty larkSuiteProperty;
    @Lazy
    @Resource
    private MasterQueueManager masterQueueManager;
    // 飞书表格中的字段名常量
    private static final String FIELD_MASTER_ID = "师父ID";
    private static final String FIELD_MASTER_NICKNAME = "师父昵称";

    @Override
    public void warmUpCache() {
        masterQueueManager.getMasterQueue();
    }

    @Override
    @Caching(evict = {@CacheEvict(cacheNames = CacheConstants.KEY_LARK_MASTER_QUEUE, allEntries = true)})
    public void refreshCache() {
        log.info("师父列表缓存已重置");
    }

    /**
     * 爬取OC收益
     */
    @Cacheable(value = CacheConstants.KEY_LARK_MASTER_QUEUE)
    public List<MasterQueueVO> getMasterQueue() {
        LarkSuiteBitTableProperty bitTable = larkSuiteProperty.findBitTable(TornConstants.BIT_TABLE_MASTER_QUEUE);
        String pageToken = null;
        boolean hasMore;
        List<MasterQueueVO> queueList = new ArrayList<>();

        do {
            final String finalPageToken = pageToken;
            SearchAppTableRecordRespBody response = larkSuiteApi.sendRequest(client -> {
                SearchAppTableRecordReq.Builder reqBuilder = SearchAppTableRecordReq.newBuilder()
                        .appToken(bitTable.getAppToken())
                        .tableId(bitTable.getTableId())
                        .pageSize(500)
                        .searchAppTableRecordReqBody(SearchAppTableRecordReqBody.newBuilder()
                                .viewId(bitTable.getViewId())
                                .build());
                if (StringUtils.hasText(finalPageToken)) {
                    reqBuilder.pageToken(finalPageToken);
                }
                return client.bitable().v1().appTableRecord().search(reqBuilder.build());
            });

            if (response == null || ArrayUtils.isEmpty(response.getItems())) {
                break;
            }

            queueList.addAll(parseRecords(response.getItems()));
            hasMore = response.getHasMore();
            pageToken = response.getPageToken();
        } while (hasMore);

        return rebuildRanks(queueList);
    }

    /**
     * 解析一批从 API 获取的记录
     */
    private List<MasterQueueVO> parseRecords(AppTableRecord[] items) {
        List<MasterQueueVO> queueList = new ArrayList<>();
        for (AppTableRecord item : items) {
            if (item == null || item.getFields() == null) {
                continue;
            }

            Map<String, Object> fields = item.getFields();
            MasterQueueVO queue = extractDataByFields(fields);
            if (queue != null) {
                queueList.add(queue);
            }
        }

        return queueList;
    }

    /**
     * 重新构造连续排名
     */
    private List<MasterQueueVO> rebuildRanks(List<MasterQueueVO> queueList) {
        List<MasterQueueVO> result = new ArrayList<>(queueList.size());
        for (int i = 0; i < queueList.size(); i++) {
            MasterQueueVO item = queueList.get(i);
            result.add(new MasterQueueVO(i + 1, item.getUserId(), item.getNickname()));
        }
        return result;
    }

    /**
     * 从一条记录的fields中，按slot顺序提取所有参与人的信息
     */
    private MasterQueueVO extractDataByFields(Map<String, Object> fields) {
        Number userIdNum = (Number) fields.get(FIELD_MASTER_ID);
        String masterNickname = LarkSuiteUtils.getTextFieldValue(fields, FIELD_MASTER_NICKNAME);
        if (userIdNum == null || !StringUtils.hasText(masterNickname)) {
            return null;
        }

        return new MasterQueueVO(0, userIdNum.longValue(), masterNickname);
    }
}