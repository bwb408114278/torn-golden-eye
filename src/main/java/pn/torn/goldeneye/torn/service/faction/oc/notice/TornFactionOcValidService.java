package pn.torn.goldeneye.torn.service.faction.oc.notice;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * OC完成逻辑
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcValidService {
    /**
     * 构建提醒
     */
    public Runnable buildNotice(LocalDateTime readyTime, Runnable refreshOc) {
        return new Notice(readyTime, refreshOc);
    }

    @AllArgsConstructor
    private class Notice implements Runnable {
        /**
         * 要执行的OC准备完成时间
         */
        private LocalDateTime readyTime;
        /**
         * 刷新OC的方式
         */
        private final Runnable refreshOc;

        @Override
        public void run() {
            refreshOc.run();
            if (LocalDateTime.now().isBefore(readyTime)) {
                checkFalseStart();
            } else {
                checkPositionFull();
            }
        }

        /**
         * 检查抢跑
         */
        private void checkFalseStart() {

        }

        /**
         * 检查车位已满
         */
        private void checkPositionFull() {

        }
    }
}