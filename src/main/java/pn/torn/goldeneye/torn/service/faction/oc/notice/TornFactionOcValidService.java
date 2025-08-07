package pn.torn.goldeneye.torn.service.faction.oc.notice;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.msg.strategy.faction.crime.OcCheckStrategyImpl;

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
    private final ApplicationContext applicationContext;

    @AllArgsConstructor
    private class Notice implements Runnable {
        /**
         * 要执行的OC准备完成时间
         */
        private LocalDateTime readyTime;

        @Override
        public void run() {
            OcCheckStrategyImpl check = applicationContext.getBean(OcCheckStrategyImpl.class);
            check.handle("");

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