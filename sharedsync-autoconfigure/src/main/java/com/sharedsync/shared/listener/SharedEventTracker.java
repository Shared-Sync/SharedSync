package com.sharedsync.shared.listener;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class SharedEventTracker {

    private static final String USER_ID = "userId";
    private final PresenceSessionManager presenceSessionManager;
    private final PresenceRootResolver presenceRootResolver;
    private final SharedSyncPresenceProperties presenceProperties;

    @EventListener
    public void handleSubscribeEvent(SessionSubscribeEvent event) {
        String channel = " ";
        if (presenceProperties.isEnabled()) {
            channel = presenceRootResolver.getChannel();
        }

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null)
            return;

        log.debug("[Presence] Subscribe event detected. destination={}, expected_channel={}", destination, channel);
        if (destination.startsWith("/topic/" + channel)) {
            String sessionId = accessor.getSessionId();
            String userId = extractUserId(accessor);
            String roomId = parseRoomId(destination);

            log.info("[Presence] Valid subscription: roomId={}, userId={}, sessionId={}", roomId, userId, sessionId);

            if (userId != null && roomId != null) {
                presenceSessionManager.handleSubscribe(roomId, userId, sessionId);
            } else {
                log.warn("[Presence] Missing metadata: userId={}, roomId={}", userId, roomId);
            }
        }
    }

    @EventListener
    public void handleDisconnectEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String userId = extractUserId(accessor);
        String sessionId = accessor.getSessionId();

        log.info("[Presence] Disconnect event: userId={}, sessionId={}", userId, sessionId);
        presenceSessionManager.handleDisconnect(userId, sessionId);
    }

    private String extractUserId(StompHeaderAccessor accessor) {
        // 1. SessionAttributes에서 확인
        Object value = accessor.getSessionAttributes().get(USER_ID);
        if (value != null) {
            return String.valueOf(value);
        }

        // 2. Principal에서 확인 (WsAuthChannelInterceptor에 의해 설정됨)
        java.security.Principal principal = accessor.getUser();
        if (principal != null) {
            String name = principal.getName();
            if (name != null && name.startsWith("u:")) {
                return name.substring(2);
            }
            return name;
        }

        return null;
    }

    private String parseRoomId(String destination) {
        if (destination == null)
            return null;
        String[] tokens = destination.split("/");
        if (tokens.length < 3) {
            return null;
        }
        // 마지막 토큰을 ID로 간주 (/topic/entity/id 형태)
        return tokens[tokens.length - 1];
    }
}