package com.sharedsync.shared.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sharedsync.shared.transport.SyncFrameExecutor;
import com.sharedsync.shared.transport.WebSocketSessionRegistry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer 구현. 앱 클래스패스에 MeterRegistry 가 있을 때만 등록된다.
 *
 * 게이지는 레지스트리의 상태를 그대로 읽는다(별도 카운팅을 두지 않는다) — 두 벌로 세면 반드시
 * 어긋나고, 어긋난 쪽이 진실인지 판단할 방법이 없다.
 */
public class MicrometerSyncMetrics implements SyncMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> frameCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final Counter rejected;

    public MicrometerSyncMetrics(MeterRegistry registry,
                                 WebSocketSessionRegistry sessions,
                                 SyncFrameExecutor executor) {
        this.registry = registry;
        this.rejected = Counter.builder("sharedsync.frames.rejected")
                .description("세션 큐 한도 초과로 거절한 인바운드 프레임")
                .register(registry);

        registry.gauge("sharedsync.ws.sessions", sessions, WebSocketSessionRegistry::sessionCount);
        registry.gauge("sharedsync.ws.rooms", sessions, WebSocketSessionRegistry::roomCount);
        // 전송 실패는 "그 클라이언트만 편집을 못 받았다"는 뜻이라 화면이 조용히 갈라진다.
        registry.gauge("sharedsync.ws.send.failures", sessions, WebSocketSessionRegistry::sendFailureCount);
        // 이 값이 계속 0 이 아니면 처리가 유입을 못 따라가는 중이다.
        registry.gauge("sharedsync.frames.pending", executor, SyncFrameExecutor::pendingFrames);
    }

    @Override
    public void frame(String type) {
        frameCounters.computeIfAbsent(type, t -> Counter.builder("sharedsync.frames")
                .tag("type", t)
                .description("인바운드 프레임")
                .register(registry)).increment();
    }

    @Override
    public void rejected() {
        rejected.increment();
    }

    @Override
    public void error(String code) {
        errorCounters.computeIfAbsent(code, c -> Counter.builder("sharedsync.errors")
                .tag("code", c)
                .description("클라이언트에 돌려준 에러 프레임")
                .register(registry)).increment();
    }
}
