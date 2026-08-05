package com.sharedsync.shared.sync;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import com.sharedsync.shared.codec.SyncCodec;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;
import com.sharedsync.shared.transport.SyncTransport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSyncService {

    private final RedisTemplate<String, RedisSyncMessage> redisSyncTemplate;
    private final SyncTransport transport;
    private final SyncCodec codec;
    private final SharedSyncWebSocketProperties props;

    /**
     * 메시지를 Redis 채널로 발행합니다.
     * 모든 서버 인스턴스가 이 메시지를 수신하여 각자의 웹소켓 클라이언트에게 전달합니다.
     *
     * 인코딩은 여기서 딱 한 번 수행하고, 그 뒤로는 바이트로만 다룬다.
     */
    public void publish(String destination, Object payload) {
        byte[] encoded = codec.encode(payload);

        if (!props.getRedisSync().isEnabled()) {
            // Redis 동기화가 비활성화된 경우 로컬로 즉시 전송
            transport.send(destination, encoded, codec.contentType());
            return;
        }
        redisSyncTemplate.convertAndSend(props.getRedisSync().getChannel(), RedisSyncMessage.builder()
                .destination(destination)
                .payload(encoded)
                .contentType(codec.contentType().toString())
                .build());
    }

    /**
     * 특정 세션에게만 메시지를 전송합니다 (지연 없이 즉시 전송용).
     *
     * 대상 세션이 다른 인스턴스에 붙어 있을 수 있으므로 브로드캐스트와 마찬가지로 Redis 를 경유한다.
     * (예전에는 로컬 convertAndSendToUser 를 직접 불러서 다른 인스턴스의 세션에는 닿지 못했다.)
     */
    public void sendToSession(String user, String sessionId, String destination, Object payload) {
        byte[] encoded = codec.encode(payload);

        if (!props.getRedisSync().isEnabled()) {
            transport.sendToSession(user, sessionId, destination, encoded, codec.contentType());
            return;
        }
        redisSyncTemplate.convertAndSend(props.getRedisSync().getChannel(), RedisSyncMessage.builder()
                .destination(destination)
                .payload(encoded)
                .contentType(codec.contentType().toString())
                .targetSessionId(sessionId)
                .targetUserId(user)
                .build());
    }

    /**
     * Redis로부터 수신한 메시지를 로컬 웹소켓 클라이언트들에게 전달합니다.
     */
    public void handleMessage(RedisSyncMessage message) {
        try {
            byte[] payload = message.getPayload();
            MimeType contentType = resolveContentType(message.getContentType());

            log.info("Redis로부터 웹소켓 동기화 메시지 수신: destination={}, bytes={}, targetSessionId={}",
                    message.getDestination(),
                    payload != null ? payload.length : 0,
                    message.getTargetSessionId());

            if (message.getTargetSessionId() != null) {
                // 대상 세션이 이 인스턴스에 없으면 각 transport 구현이 알아서 무시한다.
                transport.sendToSession(
                        message.getTargetUserId(),
                        message.getTargetSessionId(),
                        message.getDestination(),
                        payload,
                        contentType);
                return;
            }
            transport.send(message.getDestination(), payload, contentType);
        } catch (Exception e) {
            log.error("웹소켓 메시지 전달 중 오류 발생: {}", e.getMessage(), e);
        }
    }

    private MimeType resolveContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return codec.contentType();
        }
        try {
            return MimeTypeUtils.parseMimeType(raw);
        } catch (Exception e) {
            return codec.contentType();
        }
    }
}
