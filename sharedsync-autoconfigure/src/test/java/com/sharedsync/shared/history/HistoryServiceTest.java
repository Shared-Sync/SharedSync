package com.sharedsync.shared.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpAttributes;
import org.springframework.messaging.simp.SimpAttributesContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.sync.RedisSyncService;

@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ListOperations<String, Object> listOperations;

    @Mock
    private List<AutoCacheRepository<?, ?, ?>> repositories;

    @Mock
    private RedisSyncService redisSyncService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private HistoryService historyService;

    @BeforeEach
    void setUp() {
        // Set up mock SimpAttributes for getCurrentSessionId()
        SimpAttributes simpAttributes = mock(SimpAttributes.class);
        lenient().when(simpAttributes.getSessionId()).thenReturn("test-session-id");
        SimpAttributesContextHolder.setAttributes(simpAttributes);
    }

    @Test
    @DisplayName("isSupported: RedisTemplate이 주입되어 있으면 true를 반환한다")
    void isSupported_true() {
        assertTrue(historyService.isSupported());
    }

    @Test
    @DisplayName("record: 히스토리 액션을 Redis 리스트에 Push한다")
    void record_success() {
        // given
        String rootId = "root123";
        HistoryAction action = new HistoryAction();
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        // when
        historyService.record(rootId, action);

        // then
        String undoKey = "history:undo:" + rootId + ":test-session-id";
        String redoKey = "history:redo:" + rootId + ":test-session-id";

        verify(listOperations).leftPush(undoKey, action);
        verify(listOperations).trim(undoKey, 0, 49); // MAX_HISTORY - 1
        verify(redisTemplate).delete(redoKey);
    }

    @Test
    @DisplayName("clearHistory: 세션과 rootId에 해당하는 히스토리를 삭제한다")
    void clearHistory_success() {
        // given
        String rootId = "root123";
        String sessionId = "test-session-id";

        // when
        historyService.clearHistory(rootId, sessionId);

        // then
        verify(redisTemplate).delete("history:undo:" + rootId + ":" + sessionId);
        verify(redisTemplate).delete("history:redo:" + rootId + ":" + sessionId);
    }
}
