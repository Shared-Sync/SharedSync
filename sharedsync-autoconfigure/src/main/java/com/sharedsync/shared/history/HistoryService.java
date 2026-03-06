package com.sharedsync.shared.history;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpAttributesContextHolder;
import org.springframework.stereotype.Service;

import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.sync.RedisSyncService;

@Service
public class HistoryService {

    @Autowired(required = false)
    @Qualifier("presenceRedis")
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private List<AutoCacheRepository<?, ?, ?>> repositories;

    @Autowired
    private RedisSyncService redisSyncService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private static final ThreadLocal<Boolean> SKIP_HISTORY = ThreadLocal.withInitial(() -> false);

    public static void setSkipHistory(boolean skip) {
        SKIP_HISTORY.set(skip);
    }

    public static boolean isSkipHistory() {
        return Boolean.TRUE.equals(SKIP_HISTORY.get());
    }

    private String getCurrentSessionId() {
        try {
            return SimpAttributesContextHolder.currentAttributes().getSessionId();
        } catch (Exception e) {
            return null;
        }
    }

    private static final String UNDO_PREFIX = "history:undo:";
    private static final String REDO_PREFIX = "history:redo:";
    private static final int MAX_HISTORY = 50;

    public boolean isSupported() {
        return redisTemplate != null;
    }

    public void record(String rootId, HistoryAction action) {
        String sessionId = getCurrentSessionId();
        if (!isSupported() || rootId == null || sessionId == null)
            return;

        String undoKey = UNDO_PREFIX + rootId + ":" + sessionId;
        String redoKey = REDO_PREFIX + rootId + ":" + sessionId;

        redisTemplate.opsForList().leftPush(undoKey, action);
        redisTemplate.opsForList().trim(undoKey, 0, MAX_HISTORY - 1);
        redisTemplate.delete(redoKey);
    }

    public HistoryAction undo(String rootId) {
        String sessionId = getCurrentSessionId();
        HistoryAction action = popUndo(rootId, sessionId);
        if (action == null)
            return null;

        setSkipHistory(true);
        try {
            boolean success = applyInverse(action);
            if (success) {
                pushRedo(rootId, sessionId, action);
                publishChange(rootId, action, true);
                return action;
            }
            return null;
        } finally {
            setSkipHistory(false);
        }
    }

    public HistoryAction redo(String rootId) {
        String sessionId = getCurrentSessionId();
        HistoryAction action = popRedo(rootId, sessionId);
        if (action == null)
            return null;

        setSkipHistory(true);
        try {
            boolean success = applyAction(action);
            if (success) {
                pushUndo(rootId, sessionId, action);
                publishChange(rootId, action, false);
                return action;
            }
            return null;
        } finally {
            setSkipHistory(false);
        }
    }

    private void publishChange(String rootId, HistoryAction action, boolean isUndo) {
        if (redisSyncService == null || action.getEntityName() == null)
            return;

        Map<String, Object> response = new HashMap<>();
        response.put("entity", action.getEntityName());
        response.put("eventId", action.getEventId() == null ? "" : action.getEventId());
        response.put("isUndoRedo", true);

        HistoryAction.Type type = action.getType();
        String broadcastAction;
        Object data;

        if (isUndo) {
            broadcastAction = switch (type) {
                case CREATE -> "DELETE";
                case UPDATE -> "UPDATE";
                case DELETE -> "CREATE";
            };
            data = switch (type) {
                case CREATE -> action.getAfterData();
                case UPDATE -> action.getBeforeData();
                case DELETE -> action.getBeforeData();
            };
        } else {
            broadcastAction = type.name();
            data = switch (type) {
                case CREATE -> action.getAfterData();
                case UPDATE -> action.getAfterData();
                case DELETE -> action.getAfterData();
            };
        }

        response.put("action", broadcastAction.toLowerCase());
        String payloadKey = action.getEntityName().toLowerCase() + "s";
        response.put(payloadKey, data);

        redisSyncService.publish("/topic/" + rootId, response);

        if (action.getSubActions() != null) {
            for (HistoryAction subAction : action.getSubActions()) {
                publishChange(rootId, subAction, isUndo);
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private boolean applyInverse(HistoryAction action) {
        AutoCacheRepository repo = findRepository(action.getDtoClassName());
        if (repo == null)
            return false;

        boolean success = false;
        switch (action.getType()) {
            case CREATE:
                // Undo CREATE: Delete if current state matches afterData
                for (CacheDto<?> dto : (List<CacheDto<?>>) action.getAfterData()) {
                    if (!isSameState(repo.findDtoById(dto.getId()), dto))
                        return false;
                }
                repo.deleteAllById(extractIds(action.getAfterData()));
                success = true;
                break;
            case UPDATE:
                // Undo UPDATE: Restore beforeData if current state matches afterData
                for (CacheDto<?> dto : (List<CacheDto<?>>) action.getAfterData()) {
                    if (!isSameState(repo.findDtoById(dto.getId()), dto))
                        return false;
                }
                repo.saveAll(action.getBeforeData());
                success = true;
                break;
            case DELETE:
                // Undo DELETE: Restore beforeData if current state is null
                for (CacheDto<?> dto : (List<CacheDto<?>>) action.getBeforeData()) {
                    if (repo.findDtoById(dto.getId()) != null)
                        return false;
                }
                repo.saveAll(action.getBeforeData());
                // 복원된 ID를 DELETED Set에서 제거 (동기화 시 잘못 삭제되는 것을 방지)
                for (CacheDto<?> dto : (List<CacheDto<?>>) action.getBeforeData()) {
                    repo.removeFromDeletedSetUnchecked(dto.getId());
                }
                success = true;
                break;
        }

        if (success && action.getSubActions() != null) {
            for (HistoryAction subAction : action.getSubActions()) {
                applyInverse(subAction);
            }
        }
        return success;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private boolean applyAction(HistoryAction action) {
        AutoCacheRepository repo = findRepository(action.getDtoClassName());
        if (repo == null)
            return false;

        boolean success = false;
        switch (action.getType()) {
            case CREATE:
                // Redo CREATE: Save if current state is null
                for (CacheDto<?> dto : (List<CacheDto<?>>) action.getAfterData()) {
                    if (repo.findDtoById(dto.getId()) != null)
                        return false;
                }
                repo.saveAll(action.getAfterData());
                // 복원된 ID를 DELETED Set에서 제거 (이전 Undo CREATE에서 추가된 것 제거)
                for (CacheDto<?> dto : (List<CacheDto<?>>) action.getAfterData()) {
                    repo.removeFromDeletedSetUnchecked(dto.getId());
                }
                success = true;
                break;
            case UPDATE:
                // Redo UPDATE: Restore afterData if current state matches beforeData
                for (CacheDto<?> dto : (List<CacheDto<?>>) action.getBeforeData()) {
                    if (!isSameState(repo.findDtoById(dto.getId()), dto))
                        return false;
                }
                repo.saveAll(action.getAfterData());
                success = true;
                break;
            case DELETE:
                // Redo DELETE: Delete if current state matches beforeData
                for (CacheDto<?> dto : (List<CacheDto<?>>) action.getBeforeData()) {
                    if (!isSameState(repo.findDtoById(dto.getId()), dto))
                        return false;
                }
                repo.deleteAllById(extractIds(action.getBeforeData()));
                success = true;
                break;
        }

        if (success && action.getSubActions() != null) {
            for (HistoryAction subAction : action.getSubActions()) {
                applyAction(subAction);
            }
        }
        return success;
    }

    private boolean isSameState(Object current, Object expected) {
        if (current == null && expected == null)
            return true;
        if (current == null || expected == null)
            return false;
        try {
            return objectMapper.writeValueAsString(current).equals(objectMapper.writeValueAsString(expected));
        } catch (Exception e) {
            return false;
        }
    }

    private List<?> extractIds(List<? extends CacheDto<?>> dtos) {
        if (dtos == null)
            return java.util.Collections.emptyList();
        return dtos.stream()
                .map(CacheDto::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private AutoCacheRepository<?, ?, ?> findRepository(String className) {
        return repositories.stream()
                .filter(repo -> repo.getDtoClass().getName().equals(className))
                .findFirst()
                .orElse(null);
    }

    public HistoryAction popUndo(String rootId, String sessionId) {
        if (!isSupported() || rootId == null || sessionId == null)
            return null;
        return (HistoryAction) redisTemplate.opsForList().leftPop(UNDO_PREFIX + rootId + ":" + sessionId);
    }

    public void pushUndo(String rootId, String sessionId, HistoryAction action) {
        if (!isSupported() || rootId == null || sessionId == null)
            return;
        redisTemplate.opsForList().leftPush(UNDO_PREFIX + rootId + ":" + sessionId, action);
    }

    public HistoryAction popRedo(String rootId, String sessionId) {
        if (!isSupported() || rootId == null || sessionId == null)
            return null;
        return (HistoryAction) redisTemplate.opsForList().leftPop(REDO_PREFIX + rootId + ":" + sessionId);
    }

    public void pushRedo(String rootId, String sessionId, HistoryAction action) {
        if (!isSupported() || rootId == null || sessionId == null)
            return;
        redisTemplate.opsForList().leftPush(REDO_PREFIX + rootId + ":" + sessionId, action);
    }

    public void clearHistory(String rootId, String sessionId) {
        if (!isSupported() || rootId == null || sessionId == null)
            return;
        redisTemplate.delete(UNDO_PREFIX + rootId + ":" + sessionId);
        redisTemplate.delete(REDO_PREFIX + rootId + ":" + sessionId);
    }
}
