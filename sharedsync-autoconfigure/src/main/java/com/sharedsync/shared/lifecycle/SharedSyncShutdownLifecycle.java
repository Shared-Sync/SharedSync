package com.sharedsync.shared.lifecycle;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import com.sharedsync.shared.listener.PresenceSessionManager;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;
import com.sharedsync.shared.transport.SyncFrameExecutor;

import lombok.extern.slf4j.Slf4j;

/**
 * 정상 종료 때 이 파드의 세션 정리와 마지막 동기화를 Redis·DB가 멈추기 전에 끝낸다.
 *
 * <p>Spring 은 종료할 때 생명주기 단계 값이 큰 것부터 멈춘다. 웹 서버 정지(MAX - 2048)는 커넥터만 멈추고 WS 세션은
 * 닫지 않는다. Redis 연결(Lettuce)은 단계 0 에서 멈추고, {@code @PreDestroy} 는 그 뒤에 돈다. 그래서 예전처럼
 * {@code @PreDestroy} 에서 정리하면 Redis 가 이미 멈춰 전부 실패했다(유령 접속자, 퇴장 동기화 최대 1시간 지연).
 * 웹 서버 세션은 모든 빈이 정리된 맨 마지막에야 닫힌다.</p>
 *
 * <p>이 빈은 그 사이(MAX - 4096)에 멈춘다. 새 연결·새 프레임은 더 들어오지 않고, Redis·DB·프레임 실행기는 살아 있다.
 * Redis pub/sub 리스너 컨테이너(MAX)는 이미 멈춘 뒤라 이 경로는 pub/sub 구독에 기대면 안 된다.</p>
 *
 * <ol>
 *   <li>커넥터가 멈추기 전에 받아 둔 편집 프레임이 끝나길 기다린다(상한의 절반까지).</li>
 *   <li>{@link PresenceSessionManager#flushOnShutdown}: 남은 세션 퇴장 처리, 예약된 퇴장 동기화 즉시 실행.</li>
 * </ol>
 *
 * <p>전체 시간 상한은 {@code sharedsync.presence.shutdown-flush-timeout-ms}(기본 10초). Spring 의 단계별 종료 상한
 * (기본 30초)과 운영의 종료 유예(30초)보다 작아야 한다. 비정상 종료(kill -9, OOM)에서는 돌지 않는다.</p>
 */
@Slf4j
public class SharedSyncShutdownLifecycle implements SmartLifecycle {

    /** 웹 서버 정지(MAX - 2048) 뒤, Redis 연결(0) 앞. */
    public static final int PHASE = Integer.MAX_VALUE - 4096;

    private final PresenceSessionManager presenceSessionManager;
    private final ObjectProvider<SyncFrameExecutor> frameExecutor;
    private final SharedSyncPresenceProperties presenceProperties;

    private volatile boolean running;

    public SharedSyncShutdownLifecycle(PresenceSessionManager presenceSessionManager,
            ObjectProvider<SyncFrameExecutor> frameExecutor, SharedSyncPresenceProperties presenceProperties) {
        this.presenceSessionManager = presenceSessionManager;
        this.frameExecutor = frameExecutor;
        this.presenceProperties = presenceProperties;
    }

    @Override
    public void start() {
        // stop() 은 실행 중인 빈에만 불린다.
        running = true;
    }

    @Override
    public void stop() {
        long timeout = Math.max(0L, presenceProperties.getShutdownFlushTimeoutMs());
        long deadline = System.currentTimeMillis() + timeout;
        try {
            SyncFrameExecutor executor = frameExecutor.getIfAvailable();
            if (executor != null) {
                executor.awaitQuiescence(timeout / 2);
            }
            presenceSessionManager.flushOnShutdown(Math.max(0L, deadline - System.currentTimeMillis()));
        } catch (Exception e) {
            log.error("[SharedSync] 종료 정리 실패: {}", e.getMessage(), e);
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
