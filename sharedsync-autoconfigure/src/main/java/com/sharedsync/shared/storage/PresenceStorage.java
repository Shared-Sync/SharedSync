package com.sharedsync.shared.storage;

import java.util.List;
import java.util.Map;

public interface PresenceStorage {
    boolean hasTracker(String rootId);

    void insertTracker(String rootId, String sessionId, String userId, String index);

    void removeTracker(String rootId, String sessionId, String userId);

    Map<String, String> getTrackerEntries(String rootId);

    void saveUserInfo(String userId, Map<String, Object> userInfo);

    Map<String, Object> getUserInfoByUserId(String userId);

    void removeUserInfo(String userId);

    boolean isUserActiveAnywhere(String userId);

    void addActiveSession(String userId, String sessionId);

    void removeActiveSession(String userId, String sessionId);

    void mapSessionToRoot(String sessionId, String rootId, long timeoutSeconds);

    void refreshSession(String sessionId, long timeoutSeconds);

    String getRootIdBySessionId(String sessionId);

    String removeSessionRootMapping(String sessionId);

    List<String> getUserIdsInRoom(String rootId);

    List<String> purgeZombies(String rootId);

    java.util.Set<String> getAllRoomIds();

    boolean acquireSyncLock(String rootId);

    void releaseSyncLock(String rootId);

    /**
     * 소유 토큰을 가진 동기화 잠금을 잡는다. 성공하면 토큰, 이미 잠겨 있으면 빈 값.
     *
     * <p>{@link #releaseSyncLock(String, String)} 은 토큰이 같을 때만 푼다. 예전 {@link #releaseSyncLock(String)}
     * 은 조건 없이 지워서, 잠금이 만료돼 다른 동기화가 다시 잡은 잠금까지 지울 수 있었다. 키는 예전과 같아
     * 구버전 인스턴스의 {@link #acquireSyncLock(String)} 과도 서로 배제된다.</p>
     *
     * <p>기본 구현은 예전 잠금으로 대신한다(커스텀 구현체 호환용).</p>
     */
    default java.util.Optional<String> tryAcquireSyncLock(String rootId, long ttlMillis) {
        return acquireSyncLock(rootId) ? java.util.Optional.of("legacy") : java.util.Optional.empty();
    }

    /** 토큰이 같을 때만 잠금을 풀고 동기화 완료를 알린다. */
    default void releaseSyncLock(String rootId, String token) {
        releaseSyncLock(rootId);
    }

    void setIsLoading(String rootId, boolean isLoading);

    boolean isLoading(String rootId);

    /**
     * 특정 방(rootId)에 대해 현재 동기화(DB flush 등) 작업이 진행 중인지(Lock이 걸려있는지) 확인합니다.
     */
    boolean isSyncing(String rootId);

    /**
     * 특정 방(rootId)의 동기화가 완료될 때까지 대기합니다.
     */
    void waitForSync(String rootId);

    /**
     * 특정 방(rootId)의 동기화 완료 이벤트를 알립니다.
     */
    void notifySyncComplete(String rootId);

    // ===== 캐시 상태 표시 (재입장 시 다시 채울지, 주인 없는 캐시를 저장할지 판단용) =====
    // 기본 구현은 "표시 없음"이다. 커스텀 구현체는 예전처럼 매번 DB에서 다시 채우고 주인 없는 캐시를 건드리지 않는다.

    /** 이 루트의 캐시를 DB에서 끝까지 성공적으로 채웠다는 표시. 만료 없음. */
    default void markCacheReady(String rootId) {
    }

    default boolean isCacheReady(String rootId) {
        return false;
    }

    default void clearCacheReady(String rootId) {
    }

    /**
     * 트래커가 비어 있을 때만 캐시 표시를 지우고 true. 누가 들어와 있으면 아무것도 하지 않고 false.
     *
     * <p>퇴장 동기화의 커밋 후 정리가 캐시를 지우기 전에 부른다. 확인과 삭제가 한 번에 일어나야, 그 사이에
     * 들어온 사용자가 "표시가 있으니 다시 채우지 않는다"고 판단한 직후 캐시가 지워지는 일이 없다.</p>
     */
    default boolean clearCacheReadyIfNoTracker(String rootId) {
        return !hasTracker(rootId);
    }

    /** 퇴장 동기화가 예약돼 있다는 표시. 다른 파드의 주인 없는 캐시 처리가 이 방을 가로채지 않게 한다. */
    default void markPendingSync(String rootId, long ttlMillis) {
    }

    default boolean hasPendingSync(String rootId) {
        return false;
    }

    default void clearPendingSync(String rootId) {
    }

    /** 이 루트의 연속 동기화 실패 횟수를 1 올리고 올린 값을 돌려준다. 파드끼리 공유된다. */
    default int incrementSyncFailure(String rootId, long ttlMillis) {
        return 0;
    }

    default int getSyncFailureCount(String rootId) {
        return 0;
    }

    default void clearSyncFailure(String rootId) {
    }
}
