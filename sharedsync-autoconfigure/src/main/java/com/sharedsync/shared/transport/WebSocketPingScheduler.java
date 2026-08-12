package com.sharedsync.shared.transport;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 유휴 연결 유지용 서버 -> 클라 ping.
 *
 * @Scheduled 대신 자체 executor 를 쓰는 이유: 주기를 0 으로 두어 끄는 설정을 표현해야 하는데,
 * fixedDelayString 은 0 을 허용하지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
public class WebSocketPingScheduler {

    private final WebSocketSessionRegistry registry;
    private final SharedSyncWebSocketProperties props;

    private ScheduledExecutorService executor;

    @PostConstruct
    public void start() {
        int interval = props.getPingInterval();
        if (interval <= 0) {
            log.info("[SharedSync] WS ping 비활성화 (sharedsync.websocket.ping-interval=0)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sharedsync-ws-ping");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::pingQuietly, interval, interval, TimeUnit.SECONDS);
    }

    private void pingQuietly() {
        try {
            registry.pingAll();
        } catch (Exception e) {
            log.debug("[SharedSync] WS ping 주기 실행 실패: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
