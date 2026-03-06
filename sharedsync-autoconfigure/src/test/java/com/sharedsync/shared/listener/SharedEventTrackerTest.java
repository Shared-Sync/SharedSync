package com.sharedsync.shared.listener;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import com.sharedsync.shared.listener.PresenceSessionManager;
import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;

@ExtendWith(MockitoExtension.class)
class SharedEventTrackerTest {

    @Mock
    private PresenceSessionManager presenceSessionManager;

    @Mock
    private PresenceRootResolver presenceRootResolver;

    @Mock
    private SharedSyncPresenceProperties presenceProperties;

    @InjectMocks
    private SharedEventTracker sharedEventTracker;

    @Test
    @DisplayName("handleSubscribeEvent: 구독 이벤트 발생 시 userId와 roomId 파싱 후 PresenceManager에 전달한다")
    void handleSubscribeEvent_success() {
        // given
        given(presenceProperties.isEnabled()).willReturn(true);
        given(presenceRootResolver.getChannel()).willReturn("shared-room");

        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("userId", "testUser123");

        StompHeaderAccessor accessor = StompHeaderAccessor
                .create(org.springframework.messaging.simp.stomp.StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/shared-room/room-A");
        accessor.setSessionId("session-123");
        accessor.setSessionAttributes(sessionAttributes);
        Message<byte[]> message = org.springframework.messaging.support.MessageBuilder.createMessage(new byte[0],
                accessor.getMessageHeaders());

        SessionSubscribeEvent event = new SessionSubscribeEvent(this, message, null);

        // when
        sharedEventTracker.handleSubscribeEvent(event);

        // then
        verify(presenceSessionManager).handleSubscribe("room-A", "testUser123", "session-123");
    }

    @Test
    @DisplayName("handleDisconnectEvent: 연결 해제 이벤트 발생 시 PresenceManager에 전달한다")
    void handleDisconnectEvent_success() {
        // given
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("userId", "testUser456");

        StompHeaderAccessor accessor = StompHeaderAccessor
                .create(org.springframework.messaging.simp.stomp.StompCommand.DISCONNECT);
        accessor.setSessionId("session-456");
        accessor.setSessionAttributes(sessionAttributes);
        Message<byte[]> message = org.springframework.messaging.support.MessageBuilder.createMessage(new byte[0],
                accessor.getMessageHeaders());

        SessionDisconnectEvent event = new SessionDisconnectEvent(this, message, "session-456",
                org.springframework.web.socket.CloseStatus.NORMAL);

        // when
        sharedEventTracker.handleDisconnectEvent(event);

        // then
        verify(presenceSessionManager).handleDisconnect("testUser456", "session-456");
    }
}
