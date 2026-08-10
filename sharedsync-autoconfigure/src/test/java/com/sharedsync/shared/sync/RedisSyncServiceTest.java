package com.sharedsync.shared.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.MimeTypeUtils;

import com.sharedsync.shared.codec.SyncCodec;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;
import com.sharedsync.shared.transport.SyncTransport;

@ExtendWith(MockitoExtension.class)
class RedisSyncServiceTest {

    private static final byte[] ENCODED = "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);

    @Mock
    private RedisTemplate<String, RedisSyncMessage> redisSyncTemplate;

    @Mock
    private SyncTransport transport;

    @Mock
    private SyncCodec codec;

    @Mock
    private SharedSyncWebSocketProperties props;

    private RedisSyncService redisSyncService;

    @BeforeEach
    void stubCodec() {
        lenient().when(codec.encode(any())).thenReturn(ENCODED);
        lenient().when(codec.contentType()).thenReturn(MimeTypeUtils.APPLICATION_JSON);
        // codec 과 transport 는 짝(SyncChannel)으로 움직인다. 채널을 여러 개 두면 STOMP+JSON 과
        // raw WS+protobuf 를 동시에 서비스할 수 있다.
        redisSyncService = new RedisSyncService(redisSyncTemplate,
                java.util.List.of(new SyncChannel(SyncChannel.STOMP, transport, codec)), props);
    }

    private void redisSync(boolean enabled) {
        SharedSyncWebSocketProperties.RedisSync redisSyncProps = mock(SharedSyncWebSocketProperties.RedisSync.class);
        given(props.getRedisSync()).willReturn(redisSyncProps);
        given(redisSyncProps.isEnabled()).willReturn(enabled);
        if (enabled) {
            given(redisSyncProps.getChannel()).willReturn("sync-channel");
        }
    }

    @Test
    @DisplayName("publish: Redis 동기화가 비활성화 되어있으면 인코딩된 바이트를 로컬 transport 로만 보낸다")
    void publish_local() {
        redisSync(false);

        redisSyncService.publish("/topic/test", "test payload");

        verify(transport).send("/topic/test", ENCODED, MimeTypeUtils.APPLICATION_JSON);
        verifyNoInteractions(redisSyncTemplate);
    }

    @Test
    @DisplayName("publish: Redis 동기화가 활성화 되어있으면 인코딩된 바이트를 Redis 채널로 발행한다")
    void publish_redis() {
        redisSync(true);

        redisSyncService.publish("/topic/test", "test payload");

        ArgumentCaptor<RedisSyncMessage> captor = ArgumentCaptor.forClass(RedisSyncMessage.class);
        verify(redisSyncTemplate).convertAndSend(eq("sync-channel"), captor.capture());

        RedisSyncMessage message = captor.getValue();
        assertThat(message.getDestination()).isEqualTo("/topic/test");
        assertThat(message.getPayload()).isEqualTo(ENCODED);
        assertThat(message.getContentType()).isEqualTo(MimeTypeUtils.APPLICATION_JSON_VALUE);
        assertThat(message.getTargetSessionId()).as("브로드캐스트는 대상 세션이 없다").isNull();
        verifyNoInteractions(transport);
    }

    @Test
    @DisplayName("sendToSession: Redis 활성화 시에도 대상 세션 ID를 실어 Redis 를 경유한다 (다른 인스턴스의 세션에 도달하기 위해)")
    void sendToSession_goesThroughRedis() {
        redisSync(true);

        redisSyncService.sendToSession("user-123", "session-456", "/topic/private", "private payload");

        ArgumentCaptor<RedisSyncMessage> captor = ArgumentCaptor.forClass(RedisSyncMessage.class);
        verify(redisSyncTemplate).convertAndSend(eq("sync-channel"), captor.capture());

        RedisSyncMessage message = captor.getValue();
        assertThat(message.getTargetSessionId()).isEqualTo("session-456");
        assertThat(message.getTargetUserId()).isEqualTo("user-123");
        assertThat(message.getPayload()).isEqualTo(ENCODED);
        verifyNoInteractions(transport);
    }

    @Test
    @DisplayName("sendToSession: Redis 비활성화 시에는 로컬 transport 로 직접 보낸다")
    void sendToSession_local() {
        redisSync(false);

        redisSyncService.sendToSession("user-123", "session-456", "/topic/private", "private payload");

        verify(transport).sendToSession("user-123", "session-456", "/topic/private",
                ENCODED, MimeTypeUtils.APPLICATION_JSON);
        verifyNoInteractions(redisSyncTemplate);
    }

    @Test
    @DisplayName("handleMessage: 브로드캐스트 메시지는 바이트 그대로 로컬 웹소켓에 전파한다 (재직렬화 없음)")
    void handleMessage_broadcast() {
        RedisSyncMessage message = RedisSyncMessage.builder()
                .destination("/topic/updates")
                .payload(ENCODED)
                .contentType(MimeTypeUtils.APPLICATION_JSON_VALUE)
                .build();

        redisSyncService.handleMessage(message);

        verify(transport).send("/topic/updates", ENCODED, MimeTypeUtils.APPLICATION_JSON);
    }

    @Test
    @DisplayName("handleMessage: 대상 세션이 지정된 메시지는 해당 세션으로만 전달한다")
    void handleMessage_targetedSession() {
        RedisSyncMessage message = RedisSyncMessage.builder()
                .destination("/topic/plan-presence/room-1")
                .payload(ENCODED)
                .contentType(MimeTypeUtils.APPLICATION_JSON_VALUE)
                .targetSessionId("session-456")
                .targetUserId("user-123")
                .build();

        redisSyncService.handleMessage(message);

        verify(transport).sendToSession("user-123", "session-456", "/topic/plan-presence/room-1",
                ENCODED, MimeTypeUtils.APPLICATION_JSON);
        verify(transport, never()).send(any(), any(), any());
    }
}
