package com.sharedsync.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sharedsync.presence")
public class SharedSyncPresenceProperties {

    /**
     * presence 기능 사용 여부
     */
    private boolean enabled = true; // 기본값 true

    /**
     * 세션 만료 시간 (단위: 초). 기본값 1시간.
     * 이 시간 동안 활동이 없으면 좀비 데이터로 간주되어 삭제될 수 있습니다.
     */
    private long sessionTimeout = 60 * 60; // 기본값 1시간

    /**
     * 백그라운드 좀비 데이터 정리 주기 (단위: 초).
     * 0으로 설정하면 백그라운드 정리를 사용하지 않습니다.
     */
    private long cleanupInterval = 30;

    /**
     * 구독 후 최초 상태 브로드캐스트 지연 시간 (단위: 밀리초).
     * 클라이언트의 구독 처리가 완전히 완료된 후 메시지를 받도록 지연시킵니다.
     */
    private long broadcastDelay = 1000;

    /**
     * 마지막 세션 종료 시 DB 동기화 대기 시간 (단위: 초).
     * 이 기간 내에 재접속이 발생하면 동기화가 취소됩니다.
     */
    private long syncDelay = 5; // 기본값 5초

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getSessionTimeout() {
        return sessionTimeout;
    }

    public void setSessionTimeout(long sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    public long getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(long cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public long getBroadcastDelay() {
        return broadcastDelay;
    }

    public void setBroadcastDelay(long broadcastDelay) {
        this.broadcastDelay = broadcastDelay;
    }

    /**
     * 진행 중인 DB 동기화를 기다리는 폴링 횟수와 간격(ms).
     *
     * 이 대기는 입장(Join) 경로를 그 시간만큼 붙잡는다. 예전에는 500ms × 10회가 코드에 박혀 있어
     * 최악의 경우 5초를 잡았고, 그게 어디를 막는지 조정할 방법도 없었다.
     */
    private int syncWaitAttempts = 10;

    private long syncWaitIntervalMs = 500;

    public int getSyncWaitAttempts() {
        return syncWaitAttempts;
    }

    public void setSyncWaitAttempts(int syncWaitAttempts) {
        this.syncWaitAttempts = syncWaitAttempts;
    }

    public long getSyncWaitIntervalMs() {
        return syncWaitIntervalMs;
    }

    public void setSyncWaitIntervalMs(long syncWaitIntervalMs) {
        this.syncWaitIntervalMs = syncWaitIntervalMs;
    }

    public long getSyncDelay() {
        return syncDelay;
    }

    public void setSyncDelay(long syncDelay) {
        this.syncDelay = syncDelay;
    }

    /**
     * 주기적 DB 동기화 간격 (단위: 초). 기본값 30초.
     * 0 이하로 설정하면 주기적 동기화를 사용하지 않습니다.
     */
    private long periodicSyncInterval = 30;

    /**
     * 주기적 DB 동기화 활성화 여부.
     */
    private boolean periodicSyncEnabled = true;

    public long getPeriodicSyncInterval() {
        return periodicSyncInterval;
    }

    public void setPeriodicSyncInterval(long periodicSyncInterval) {
        this.periodicSyncInterval = periodicSyncInterval;
    }

    public boolean isPeriodicSyncEnabled() {
        return periodicSyncEnabled;
    }

    public void setPeriodicSyncEnabled(boolean periodicSyncEnabled) {
        this.periodicSyncEnabled = periodicSyncEnabled;
    }

    /**
     * 동기화 잠금(PRESENCE:SYNC_LOCK)의 유지 시간(ms). 기본값 30초 — 예전 고정값과 같다.
     * 동기화가 이보다 오래 걸리면 잠금이 먼저 풀려 다른 동기화와 겹칠 수 있으므로,
     * {@link #slowSyncWarnMs} 를 넘기면 경고를 남긴다.
     */
    private long syncLockTtlMs = 30_000;

    /**
     * 퇴장 동기화가 잠금을 못 잡았을 때 다시 시도하는 횟수와 간격(ms).
     * 예전에는 재시도 없이 건너뛰어, 마침 주기 동기화가 잠금을 쥔 순간 나간 방은 마지막 동기화가 사라졌다.
     */
    private int syncLockRetryAttempts = 10;

    private long syncLockRetryIntervalMs = 1_000;

    /** 동기화 한 번이 이 시간(ms)을 넘기면 경고한다. 잠금 TTL 에 가까워지는 걸 미리 알기 위함. */
    private long slowSyncWarnMs = 10_000;

    /** 같은 방의 동기화가 이 횟수 이상 연속 실패하면 ERROR 로 알린다. 동작은 바꾸지 않는다. */
    private int syncFailureAlertThreshold = 3;

    public long getSyncLockTtlMs() {
        return syncLockTtlMs;
    }

    public void setSyncLockTtlMs(long syncLockTtlMs) {
        this.syncLockTtlMs = syncLockTtlMs;
    }

    public int getSyncLockRetryAttempts() {
        return syncLockRetryAttempts;
    }

    public void setSyncLockRetryAttempts(int syncLockRetryAttempts) {
        this.syncLockRetryAttempts = syncLockRetryAttempts;
    }

    public long getSyncLockRetryIntervalMs() {
        return syncLockRetryIntervalMs;
    }

    public void setSyncLockRetryIntervalMs(long syncLockRetryIntervalMs) {
        this.syncLockRetryIntervalMs = syncLockRetryIntervalMs;
    }

    public long getSlowSyncWarnMs() {
        return slowSyncWarnMs;
    }

    public void setSlowSyncWarnMs(long slowSyncWarnMs) {
        this.slowSyncWarnMs = slowSyncWarnMs;
    }

    public int getSyncFailureAlertThreshold() {
        return syncFailureAlertThreshold;
    }

    public void setSyncFailureAlertThreshold(int syncFailureAlertThreshold) {
        this.syncFailureAlertThreshold = syncFailureAlertThreshold;
    }

    /**
     * 연속 실패 횟수(SYNC_FAIL_COUNT)의 유지 시간(초). 이 시간 동안 새 실패가 없으면 횟수가 사라진다.
     * 연속 실패가 {@link #syncFailureAlertThreshold} 이상인 방은 "막힌 방"으로 보고, 재입장 때 남은 캐시를 쓰지 않고
     * 예전처럼 DB에서 다시 채운다(주인 없는 캐시 처리도 건너뛴다).
     */
    private long syncFailureCountTtlSeconds = 60 * 60;

    /** 정상 종료 때 세션 정리와 마지막 동기화에 쓰는 전체 시간 상한(ms). Spring 의 단계별 종료 상한(30초)보다 작아야 한다. */
    private long shutdownFlushTimeoutMs = 10_000;

    /** 접속자도 예약된 퇴장 동기화도 없이 남은 캐시를 주기 동기화가 저장할지. */
    private boolean orphanSyncEnabled = true;

    /** 주인 없는 캐시로 처음 보인 뒤 이 시간(초)이 지나야 저장한다. 퇴장 지연과 재시도 전체보다 충분히 길어야 한다. */
    private long orphanGraceSeconds = 120;

    /** 퇴장 동기화 예약 표시(PRESENCE:PENDING_SYNC)의 유지 시간(ms). 퇴장 지연 + 잠금 재시도 전체보다 길어야 한다. */
    private long pendingSyncMarkerTtlMs = 60_000;

    /**
     * 동기화 트랜잭션에서 캐시 엔티티 테이블의 유니크 제약을 커밋 때 검사하게 할지. 앱이 제약을
     * {@code DEFERRABLE INITIALLY IMMEDIATE} 로 선언한 경우에만 효과가 있다(PostgreSQL). 켜 두면 행을 쓰는 순서
     * 때문에 최종 상태가 정상인 편집이 유니크 제약에 걸리지 않는다.
     */
    private boolean deferUniqueConstraints = true;

    public boolean isDeferUniqueConstraints() {
        return deferUniqueConstraints;
    }

    public void setDeferUniqueConstraints(boolean deferUniqueConstraints) {
        this.deferUniqueConstraints = deferUniqueConstraints;
    }

    public long getSyncFailureCountTtlSeconds() {
        return syncFailureCountTtlSeconds;
    }

    public void setSyncFailureCountTtlSeconds(long syncFailureCountTtlSeconds) {
        this.syncFailureCountTtlSeconds = syncFailureCountTtlSeconds;
    }

    public long getShutdownFlushTimeoutMs() {
        return shutdownFlushTimeoutMs;
    }

    public void setShutdownFlushTimeoutMs(long shutdownFlushTimeoutMs) {
        this.shutdownFlushTimeoutMs = shutdownFlushTimeoutMs;
    }

    public boolean isOrphanSyncEnabled() {
        return orphanSyncEnabled;
    }

    public void setOrphanSyncEnabled(boolean orphanSyncEnabled) {
        this.orphanSyncEnabled = orphanSyncEnabled;
    }

    public long getOrphanGraceSeconds() {
        return orphanGraceSeconds;
    }

    public void setOrphanGraceSeconds(long orphanGraceSeconds) {
        this.orphanGraceSeconds = orphanGraceSeconds;
    }

    public long getPendingSyncMarkerTtlMs() {
        return pendingSyncMarkerTtlMs;
    }

    public void setPendingSyncMarkerTtlMs(long pendingSyncMarkerTtlMs) {
        this.pendingSyncMarkerTtlMs = pendingSyncMarkerTtlMs;
    }
}
