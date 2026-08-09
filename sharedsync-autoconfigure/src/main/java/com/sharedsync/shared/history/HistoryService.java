package com.sharedsync.shared.history;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.sharedsync.shared.codec.SyncOutbound;
import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.sync.RedisSyncService;
import com.sharedsync.shared.transport.SyncSessionContext;

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

    @Autowired
    private SyncSessionContext sessionContext;

    private static final ThreadLocal<Boolean> SKIP_HISTORY = ThreadLocal.withInitial(() -> false);

    public static void setSkipHistory(boolean skip) {
        SKIP_HISTORY.set(skip);
    }

    public static boolean isSkipHistory() {
        return Boolean.TRUE.equals(SKIP_HISTORY.get());
    }

    private String getCurrentSessionId() {
        // transport 중립. STOMP 이면 SimpAttributes, raw WS 면 핸들러가 채운 ThreadLocal 에서 나온다.
        // 여기서 null 이 나오면 undo 히스토리가 조용히 기록되지 않으므로 transport 구현이
        // 반드시 세션을 채워야 한다.
        return sessionContext.currentSessionId();
    }

    private static final String UNDO_PREFIX = "history:undo:";
    private static final String REDO_PREFIX = "history:redo:";
    private static final int MAX_HISTORY = 50;

    public boolean isSupported() {
        return redisTemplate != null;
    }

    /**
     * undo/redo 는 Redis 가 있어야 동작한다. 없으면 record/undo/redo 가 전부 조용히 no-op 이 되어
     * "undo 눌러도 아무 일도 안 일어난다"로만 드러난다. 기동 시 한 번 알린다.
     */
    @jakarta.annotation.PostConstruct
    void warnIfDisabled() {
        if (!isSupported()) {
            LoggerFactory.getLogger(HistoryService.class).warn(
                    "[SharedSync] presenceRedis RedisTemplate 이 없어 undo/redo 히스토리가 비활성화된다. "
                            + "편집은 정상 동작하지만 undo 요청은 아무 것도 하지 않는다.");
        }
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

        // 예전에는 페이로드 키를 entityName.toLowerCase()+"s" 로 런타임에 만들어서 일반 편집
        // 응답("timeTablePlaceBlockDtos")과 모양이 달랐다. 이제 SyncOutbound 로 통일한다 —
        // 두 클라이언트 모두 두 키를 모두 받아들이는 폴백이 있어 기존 배포본과도 호환된다.
        @SuppressWarnings("unchecked")
        List<? extends CacheDto<?>> dtos = (List<? extends CacheDto<?>>) data;

        redisSyncService.publish("/topic/" + rootId, new SyncOutbound.Entities(
                action.getEventId() == null ? "" : action.getEventId(),
                broadcastAction.toLowerCase(),
                action.getEntityName(),
                true,
                SyncOutbound.dtoFieldNameOf(action.getDtoClassName()),
                dtos == null ? List.of() : dtos));

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
