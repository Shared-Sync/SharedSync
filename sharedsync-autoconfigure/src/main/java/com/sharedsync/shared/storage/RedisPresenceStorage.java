package com.sharedsync.shared.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
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

    /** 토큰이 같을 때만 지운다. GET 후 DEL 을 따로 하면 그 사이 다른 쪽이 잡은 잠금을 지울 수 있다. */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> RELEASE_IF_OWNER =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    @Override
    public java.util.Optional<String> tryAcquireSyncLock(String rootId, long ttlMillis) {
        String token = java.util.UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(SYNC_LOCK + rootId, token,
                java.time.Duration.ofMillis(ttlMillis));
        return Boolean.TRUE.equals(acquired) ? java.util.Optional.of(token) : java.util.Optional.empty();
    }

    @Override
    public void releaseSyncLock(String rootId, String token) {
        Long removed = redis.execute(RELEASE_IF_OWNER, List.of(SYNC_LOCK + rootId), token);
        if (removed == null || removed == 0L) {
            // 이미 만료돼 다른 동기화가 잡았거나 풀렸다. 남의 잠금은 지우지 않는다.
            log.warn("[RedisStorage] 동기화 잠금이 이미 다른 소유자로 바뀌었거나 만료됨 rootId={}", rootId);
        }
        notifySyncComplete(rootId);
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

    // ===== 캐시 상태 표시 =====

    private static final String CACHE_READY = "CACHE:READY:";
    private static final String PENDING_SYNC = "PRESENCE:PENDING_SYNC:";
    private static final String SYNC_FAIL_COUNT = "SYNC_FAIL_COUNT:";

    /** 트래커 키가 없을 때만 표시를 지운다. 빈 해시는 Redis 가 키째 지우므로 EXISTS 로 충분하다. */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> CLEAR_READY_IF_NO_TRACKER =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('exists', KEYS[1]) == 0 then redis.call('del', KEYS[2]) return 1 else return 0 end",
                    Long.class);

    @Override
    public void markCacheReady(String rootId) {
        redis.opsForValue().set(CACHE_READY + rootId, "1");
    }

    @Override
    public boolean isCacheReady(String rootId) {
        return Boolean.TRUE.equals(redis.hasKey(CACHE_READY + rootId));
    }

    @Override
    public void clearCacheReady(String rootId) {
        redis.delete(CACHE_READY + rootId);
    }

    @Override
    public boolean clearCacheReadyIfNoTracker(String rootId) {
        Long cleared = redis.execute(CLEAR_READY_IF_NO_TRACKER, List.of(TRACKER + rootId, CACHE_READY + rootId));
        return cleared != null && cleared == 1L;
    }

    @Override
    public void markPendingSync(String rootId, long ttlMillis) {
        redis.opsForValue().set(PENDING_SYNC + rootId, "1", java.time.Duration.ofMillis(ttlMillis));
    }

    @Override
    public boolean hasPendingSync(String rootId) {
        return Boolean.TRUE.equals(redis.hasKey(PENDING_SYNC + rootId));
    }

    @Override
    public void clearPendingSync(String rootId) {
        redis.delete(PENDING_SYNC + rootId);
    }

    @Override
    public int incrementSyncFailure(String rootId, long ttlMillis) {
        String key = SYNC_FAIL_COUNT + rootId;
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, java.time.Duration.ofMillis(ttlMillis));
        return count == null ? 0 : count.intValue();
    }

    @Override
    public int getSyncFailureCount(String rootId) {
        // INCR 로 쓴 값이라 JSON 직렬화기가 숫자로 읽는다.
        Object value = redis.opsForValue().get(SYNC_FAIL_COUNT + rootId);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    @Override
    public void clearSyncFailure(String rootId) {
        redis.delete(SYNC_FAIL_COUNT + rootId);
    }

}
