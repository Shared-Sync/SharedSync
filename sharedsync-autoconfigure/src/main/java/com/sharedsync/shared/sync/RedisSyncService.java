package com.sharedsync.shared.sync;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSyncService {

    private final RedisTemplate<String, RedisSyncMessage> redisSyncTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final SharedSyncWebSocketProperties props;

    /**
     * 메시지를 Redis 채널로 발행합니다.
     * 모든 서버 인스턴스가 이 메시지를 수신하여 각자의 웹소켓 클라이언트에게 전달합니다.
     */
    public void publish(String destination, Object payload) {
        if (!props.getRedisSync().isEnabled()) {
            // Redis 동기화가 비활성화된 경우 로컬로 즉시 전송
            messagingTemplate.convertAndSend(destination, payload);
            return;
        }
        RedisSyncMessage message = RedisSyncMessage.builder()
                .destination(destination)
                .payload(payload)
                .build();
        redisSyncTemplate.convertAndSend(props.getRedisSync().getChannel(), message);
    }

    /**
     * 특정 세션에게만 메시지를 전송합니다 (지연 없이 즉시 전송용).
     */
    public void sendToSession(String user, String sessionId, String destination, Object payload) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setLeaveMutable(true);
        
        // 특정 유저의 특정 세션을 타겟으로 전송
        messagingTemplate.convertAndSendToUser(
                user, 
                destination, 
                payload, 
                headerAccessor.getMessageHeaders()
        );
    }

    /**
     * Redis로부터 수신한 메시지를 로컬 웹소켓 클라이언트들에게 전달합니다.
     */
    public void handleMessage(RedisSyncMessage message) {
        try {
            log.info("Redis로부터 웹소켓 동기화 메시지 수신: destination={}, payloadType={}", 
                    message.getDestination(), 
                    message.getPayload() != null ? message.getPayload().getClass().getSimpleName() : "null");
            
            messagingTemplate.convertAndSend(message.getDestination(), message.getPayload());
        } catch (Exception e) {
            log.error("웹소켓 메시지 전달 중 오류 발생: {}", e.getMessage(), e);
        }
    }
}
