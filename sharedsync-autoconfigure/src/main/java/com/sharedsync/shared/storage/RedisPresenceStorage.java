package com.sharedsync.shared.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@ConditionalOnProperty(name = "sharedsync.cache.type", havingValue = "redis")
public class RedisPresenceStorage implements PresenceStorage {

    private final RedisTemplate<String, Object> redis;

    private static final String TRACKER = "PRESENCE:TRACKER:";
    private static final String SESSION_TO_ROOT = "PRESENCE:SESSION_ROOT:";
    private static final String USER_INFO = "PRESENCE:USER_INFO:";
    private static final String USER_SESSIONS = "PRESENCE:USER_SESSIONS:";
    private static final String SYNC_LOCK = "PRESENCE:SYNC_LOCK:";

    @Override
    public boolean hasTracker(String rootId) {
        return redis.hasKey(TRACKER + rootId);
    }

    @Override
    public void insertTracker(String rootId, String sessionId, String userId, String index) {
        redis.opsForHash().put(TRACKER + rootId, String.valueOf(userId + "//" + sessionId), index);
    }

    @Override
    public void removeTracker(String rootId, String sessionId, String userId) {
        redis.opsForHash().delete(TRACKER + rootId, String.valueOf(userId + "//" + sessionId));
    }

    @Override
    public Map<String, String> getTrackerEntries(String rootId) {
        Map<Object, Object> entries = redis.opsForHash().entries(TRACKER + rootId);
        Map<String, String> result = new java.util.HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            try {
                String key = entry.getKey().toString();
                String value = entry.getValue().toString();
                result.put(key, value);
            } catch (NumberFormatException e) {
                // skip invalid entries
            }
        }
        return result;
    }

    @Override
    public void saveUserInfo(String userId, Map<String, Object> userInfo) {
        redis.opsForHash().putAll(USER_INFO + userId, userInfo);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserInfoByUserId(String userId) {
        Map<Object, Object> entries = redis.opsForHash().entries(USER_INFO + userId);
        if (entries.isEmpty())
            return null;

        Map<String, Object> result = new java.util.HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v));
        return result;
    }

    @Override
    public void removeUserInfo(String userId) {
        redis.delete(USER_INFO + userId);
        redis.delete(USER_SESSIONS + userId);
    }

    @Override
    public boolean isUserActiveAnywhere(String userId) {
        Long size = redis.opsForSet().size(USER_SESSIONS + userId);
        return size != null && size > 0;
    }

    @Override
    public void addActiveSession(String userId, String sessionId) {
        redis.opsForSet().add(USER_SESSIONS + userId, sessionId);
    }

    @Override
    public void removeActiveSession(String userId, String sessionId) {
        redis.opsForSet().remove(USER_SESSIONS + userId, sessionId);
    }

    @Override
    public void mapSessionToRoot(String sessionId, String rootId, long timeoutSeconds) {
        redis.opsForValue().set(SESSION_TO_ROOT + sessionId, rootId, java.time.Duration.ofSeconds(timeoutSeconds));
    }

    @Override
    public void refreshSession(String sessionId, long timeoutSeconds) {
        redis.expire(SESSION_TO_ROOT + sessionId, java.time.Duration.ofSeconds(timeoutSeconds));
    }

    @Override
    public String getRootIdBySessionId(String sessionId) {
        Object v = redis.opsForValue().get(SESSION_TO_ROOT + sessionId);
        return v == null ? null : (String) v;
    }

    @Override
    public String removeSessionRootMapping(String sessionId) {
        Object v = redis.opsForValue().getAndDelete(SESSION_TO_ROOT + sessionId);
        return v == null ? null : (String) v;
    }

    @Override
    public List<String> getUserIdsInRoom(String rootId) {
        return cleanAndGetActiveUserIds(rootId).activeUserIds();
    }

    @Override
    public List<String> purgeZombies(String rootId) {
        return cleanAndGetActiveUserIds(rootId).removedUserIds();
    }

    private record CleanupResult(List<String> activeUserIds, List<String> removedUserIds) {
    }

    private CleanupResult cleanAndGetActiveUserIds(String rootId) {
        Map<Object, Object> entries = redis.opsForHash().entries(TRACKER + rootId);
        if (entries.isEmpty()) {
            redis.delete(TRACKER + rootId);
            return new CleanupResult(new ArrayList<>(), new ArrayList<>());
        }

        List<String> trackerKeys = new ArrayList<>();
        List<String> sessionRedisKeys = new ArrayList<>();
        List<String> candidateUserIds = new ArrayList<>();

        for (Object k : entries.keySet()) {
            String key = k.toString();
            String[] parts = key.split("//");
            if (parts.length < 2)
                continue;

            trackerKeys.add(key);
            sessionRedisKeys.add(SESSION_TO_ROOT + parts[1]);
            candidateUserIds.add(parts[0]);
        }

        List<Object> sessionValues = new ArrayList<>();
        for (String sessionKey : sessionRedisKeys) {
            sessionValues.add(redis.opsForValue().get(sessionKey));
        }

        log.debug("[RedisStorage] Checking session activity for rootId={}, sessionCount={}", rootId,
                sessionRedisKeys.size());
        List<String> activeUserIds = new ArrayList<>();
        List<String> removedUserIds = new ArrayList<>();

        for (int i = 0; i < sessionRedisKeys.size(); i++) {
            if (sessionValues != null && i < sessionValues.size() && sessionValues.get(i) != null) {
                activeUserIds.add(candidateUserIds.get(i));
            } else {
                log.info("[RedisStorage] Session expired or invalid, cleaning up: {}", trackerKeys.get(i));
                redis.opsForHash().delete(TRACKER + rootId, trackerKeys.get(i));
                removedUserIds.add(trackerKeys.get(i)); // userId//sessionId 형태의 전체 키 추가
            }
        }

        if (activeUserIds.isEmpty()) {
            log.info("[RedisStorage] No active users left in room {}, deleting tracker key", rootId);
            redis.delete(TRACKER + rootId);
        }

        return new CleanupResult(activeUserIds, removedUserIds);
    }

    @Override
    public java.util.Set<String> getAllRoomIds() {
        return redis.execute((org.springframework.data.redis.connection.RedisConnection connection) -> {
            java.util.Set<String> roomIds = new java.util.HashSet<>();
            try (org.springframework.data.redis.core.Cursor<byte[]> cursor = connection
                    .scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(TRACKER + "*").count(100)
                            .build())) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next());
                    roomIds.add(key.substring(TRACKER.length()));
                }
            } catch (Exception e) {
                log.error("Error scanning keys", e);
            }
            return roomIds;
        });
    }

    @Override
    public boolean acquireSyncLock(String rootId) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(SYNC_LOCK + rootId, "locked", java.time.Duration.ofSeconds(30)));
    }

    @Override
    public void setIsLoading(String rootId, boolean isLoading) {
        String key = "CACHE:LOADING:" + rootId;
        if (isLoading) {
            redis.opsForValue().set(key, "TRUE", java.time.Duration.ofSeconds(60));
        } else {
            redis.delete(key);
        }
    }

    @Override
    public boolean isLoading(String rootId) {
        String key = "CACHE:LOADING:" + rootId;
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    @Override
    public boolean isSyncing(String rootId) {
        return Boolean.TRUE.equals(redis.hasKey(SYNC_LOCK + rootId));
    }

    private final org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

    public RedisPresenceStorage(
            @org.springframework.beans.factory.annotation.Qualifier("presenceRedis") RedisTemplate<String, Object> redis,
            @org.springframework.beans.factory.annotation.Qualifier("sharedSyncRedisMessageListenerContainer") org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer) {
        this.redis = redis;
        this.redisMessageListenerContainer = redisMessageListenerContainer;
    }

    @Override
    public void waitForSync(String rootId) {
        if (!isSyncing(rootId)) {
            return;
        }

        String channel = "PRESENCE:SYNC_COMPLETE:" + rootId;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        org.springframework.data.redis.connection.MessageListener listener = (message, pattern) -> {
            latch.countDown();
        };

        try {
            redisMessageListenerContainer.addMessageListener(listener,
                    new org.springframework.data.redis.listener.ChannelTopic(channel));

            // Re-check locking status to avoid race condition (sync might have finished
            // before subscription)
            if (!isSyncing(rootId)) {
                return;
            }

            // Wait for up to 5 seconds
            try {
                if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("[RedisStorage] Timeout waiting for sync completion: rootId={}", rootId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            redisMessageListenerContainer.removeMessageListener(listener);
        }
    }

    @Override
    public void notifySyncComplete(String rootId) {
        redis.convertAndSend("PRESENCE:SYNC_COMPLETE:" + rootId, "DONE");
    }

    @Override
    public void releaseSyncLock(String rootId) {
        redis.delete(SYNC_LOCK + rootId);
        notifySyncComplete(rootId);
    }

}
