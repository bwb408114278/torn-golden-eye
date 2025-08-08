package pn.torn.goldeneye.torn.service.faction.oc.notice;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;

/**
 * OC完成逻辑
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcCompleteService {
    private final TornFactionOcDAO ocDao;

    /**
     * 构建提醒
     *
     * @param id oc ID
     */
    public Runnable buildNotice(long id, Runnable refreshOc) {
        return new Notice(id, refreshOc);
    }

    @AllArgsConstructor
    private class Notice implements Runnable {
        /**
         * OC级别
         */
        private final long id;
        /**
         * 刷新OC的方式
         */
        private final Runnable refreshOc;

        @Override
        public void run() {
            ocDao.updateCompleted(id);
            refreshOc.run();
        }
    }
}