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
}
