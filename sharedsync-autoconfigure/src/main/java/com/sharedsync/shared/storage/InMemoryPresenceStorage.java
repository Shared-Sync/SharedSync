package com.sharedsync.shared.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 인메모리 기반 PresenceStorage 구현체.
 * 단일 인스턴스 환경에 적합합니다.
 */
@Component
@ConditionalOnProperty(name = "sharedsync.cache.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryPresenceStorage implements PresenceStorage {

    // rootId -> Map<userId//sessionId, index>
    private final Map<String, Map<String, String>> trackers = new ConcurrentHashMap<>();

    // sessionId -> rootId
    private final Map<String, String> sessionToRoot = new ConcurrentHashMap<>();

    // userId -> userInfo
    private final Map<String, Map<String, Object>> userInfos = new ConcurrentHashMap<>();

    // userId -> Set<sessionId>
    private final Map<String, java.util.Set<String>> userSessions = new ConcurrentHashMap<>();

    // rootId -> lock
    private final java.util.Set<String> syncLocks = ConcurrentHashMap.newKeySet();

    @Override
    public boolean hasTracker(String rootId) {
        Map<String, String> tracker = trackers.get(rootId);
        return tracker != null && !tracker.isEmpty();
    }

    @Override
    public void insertTracker(String rootId, String sessionId, String userId, String index) {
        trackers.computeIfAbsent(rootId, k -> new ConcurrentHashMap<>())
                .put(userId + "//" + sessionId, index);
    }

    @Override
    public void removeTracker(String rootId, String sessionId, String userId) {
        Map<String, String> tracker = trackers.get(rootId);
        if (tracker != null) {
            tracker.remove(userId + "//" + sessionId);
            if (tracker.isEmpty()) {
                trackers.remove(rootId);
            }
        }
    }

    @Override
    public Map<String, String> getTrackerEntries(String rootId) {
        Map<String, String> tracker = trackers.get(rootId);
        if (tracker == null) {
            return new ConcurrentHashMap<>();
        }
        return new ConcurrentHashMap<>(tracker);
    }

    @Override
    public void saveUserInfo(String userId, Map<String, Object> userInfo) {
        userInfos.put(userId, userInfo);
    }

    @Override
    public Map<String, Object> getUserInfoByUserId(String userId) {
        return userInfos.get(userId);
    }

    @Override
    public void removeUserInfo(String userId) {
        userInfos.remove(userId);
        userSessions.remove(userId);
    }

    @Override
    public boolean isUserActiveAnywhere(String userId) {
        java.util.Set<String> sessions = userSessions.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    @Override
    public void addActiveSession(String userId, String sessionId) {
        userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(sessionId);
    }

    @Override
    public void removeActiveSession(String userId, String sessionId) {
        java.util.Set<String> sessions = userSessions.get(userId);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                userSessions.remove(userId);
            }
        }
    }

    @Override
    public void mapSessionToRoot(String sessionId, String rootId, long timeoutSeconds) {
        sessionToRoot.put(sessionId, rootId);
    }

    @Override
    public void refreshSession(String sessionId, long timeoutSeconds) {
        // 인메모리에서는 별도 처리 하지 않음 (필요 시 세션 타임아웃 맵 관리)
    }

    @Override
    public String getRootIdBySessionId(String sessionId) {
        return sessionToRoot.get(sessionId);
    }

    @Override
    public String removeSessionRootMapping(String sessionId) {
        return sessionToRoot.remove(sessionId);
    }

    @Override
    public List<String> getUserIdsInRoom(String rootId) {
        Map<String, String> tracker = trackers.get(rootId);
        if (tracker == null) {
            return new ArrayList<>();
        }
        List<String> userIds = new ArrayList<>();
        for (String key : tracker.keySet()) {
            String userId = key.split("//")[0]; // "userId//sessionId" → userId만 추출
            userIds.add(userId);
        }
        return userIds;
    }

    @Override
    public List<String> purgeZombies(String rootId) {
        return new ArrayList<>(); // 메모리 방식은 TTL이 없으므로 좀비가 없음
    }

    @Override
    public java.util.Set<String> getAllRoomIds() {
        return trackers.keySet();
    }

    @Override
    public boolean acquireSyncLock(String rootId) {
        return syncLocks.add(rootId);
    }

    private final java.util.Set<String> loadingFlags = ConcurrentHashMap.newKeySet();

    @Override
    public void setIsLoading(String rootId, boolean isLoading) {
        if (isLoading) {
            loadingFlags.add(rootId);
        } else {
            loadingFlags.remove(rootId);
        }
    }

    @Override
    public boolean isLoading(String rootId) {
        return loadingFlags.contains(rootId);
    }

    private final Map<String, java.util.concurrent.locks.Condition> syncConditions = new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    @Override
    public boolean isSyncing(String rootId) {
        return syncLocks.contains(rootId);
    }

    @Override
    public void waitForSync(String rootId) {
        lock.lock();
        try {
            while (isSyncing(rootId)) {
                java.util.concurrent.locks.Condition condition = syncConditions.computeIfAbsent(rootId,
                        k -> lock.newCondition());
                try {
                    if (!condition.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        break; // timeout
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void notifySyncComplete(String rootId) {
        lock.lock();
        try {
            java.util.concurrent.locks.Condition condition = syncConditions.remove(rootId);
            if (condition != null) {
                condition.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void releaseSyncLock(String rootId) {
        boolean removed = syncLocks.remove(rootId);
        if (removed) {
            notifySyncComplete(rootId);
        }
    }
}
