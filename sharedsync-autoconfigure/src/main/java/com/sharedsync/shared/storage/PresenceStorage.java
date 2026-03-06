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
