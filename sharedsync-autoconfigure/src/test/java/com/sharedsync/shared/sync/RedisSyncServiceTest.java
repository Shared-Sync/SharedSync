package com.sharedsync.shared.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;

@ExtendWith(MockitoExtension.class)
class RedisSyncServiceTest {

    @Mock
    private RedisTemplate<String, RedisSyncMessage> redisSyncTemplate;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private SharedSyncWebSocketProperties props;

    @InjectMocks
    private RedisSyncService redisSyncService;

    @Test
    @DisplayName("publish: Redis 동기화가 비활성화 되어있으면 로컬 심플 메시징만 수행한다")
    void publish_local() {
        // given
        SharedSyncWebSocketProperties.RedisSync redisSyncProps = mock(SharedSyncWebSocketProperties.RedisSync.class);
        given(props.getRedisSync()).willReturn(redisSyncProps);
        given(redisSyncProps.isEnabled()).willReturn(false);

        String destination = "/topic/test";
        Object payload = "test payload";

        // when
        redisSyncService.publish(destination, payload);

        // then
        verify(messagingTemplate).convertAndSend(destination, payload);
        verifyNoInteractions(redisSyncTemplate);
    }

    @Test
    @DisplayName("publish: Redis 동기화가 활성화 되어있으면 Redis 채널로 메시지를 발행한다")
    void publish_redis() {
        // given
        SharedSyncWebSocketProperties.RedisSync redisSyncProps = mock(SharedSyncWebSocketProperties.RedisSync.class);
        given(props.getRedisSync()).willReturn(redisSyncProps);
        given(redisSyncProps.isEnabled()).willReturn(true);
        given(redisSyncProps.getChannel()).willReturn("sync-channel");

        String destination = "/topic/test";
        Object payload = "test payload";

        // when
        redisSyncService.publish(destination, payload);

        // then
        ArgumentCaptor<RedisSyncMessage> captor = ArgumentCaptor.forClass(RedisSyncMessage.class);
        verify(redisSyncTemplate).convertAndSend(eq("sync-channel"), captor.capture());

        RedisSyncMessage message = captor.getValue();
        assert message.getDestination().equals(destination);
        assert message.getPayload().equals(payload);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("sendToSession: 특정 세션을 대상으로 메시지를 전송한다")
    void sendToSession_success() {
        // given
        String user = "user-123";
        String sessionId = "session-456";
        String destination = "/queue/private";
        Object payload = "private payload";

        // when
        redisSyncService.sendToSession(user, sessionId, destination, payload);

        // then
        verify(messagingTemplate).convertAndSendToUser(
                eq(user),
                eq(destination),
                eq(payload),
                any(Map.class));
    }

    @Test
    @DisplayName("handleMessage: Redis 브로드캐스팅 메시지 수신 시 로컬 웹소켓에 전파한다")
    void handleMessage_success() {
        // given
        RedisSyncMessage message = RedisSyncMessage.builder()
                .destination("/topic/updates")
                .payload("update data")
                .build();

        // when
        redisSyncService.handleMessage(message);

        // then
        verify(messagingTemplate).convertAndSend("/topic/updates", "update data");
    }
}
