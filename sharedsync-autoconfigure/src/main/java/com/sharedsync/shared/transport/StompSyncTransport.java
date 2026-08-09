package com.sharedsync.shared.transport;

import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeType;

import lombok.RequiredArgsConstructor;

/**
 * STOMP 브로커 위임 구현.
 *
 * 이미 인코딩된 바이트를 보내야 하므로 convertAndSend(POJO) 대신 Message 를 직접 만들어
 * send() 한다 — 컨버터 체인을 타지 않으므로 codec 이 만든 바이트가 그대로 프레임 본문이 된다.
 * content-type 헤더는 SimpMessagingTemplate.doSend 가 보존한다.
 *
 * JSON codec 기준으로 클라이언트가 보는 프레임은 MappingJackson2MessageConverter 를 쓰던 때와
 * 동일하다: content-type 이 octet-stream 이 아니므로 StompSubProtocolHandler 가 TextMessage 로
 * 내보내고, 바이트는 그대로 UTF-8 JSON 이다.
 *
 * 빈 등록은 SharedWebSocketConfig 가 한다 — @Component 로 두면 STOMP 를 끈 raw WS 모드에서
 * 존재하지 않는 SimpMessagingTemplate 을 주입받으려다 기동이 깨진다.
 */
@RequiredArgsConstructor
public class StompSyncTransport implements SyncTransport {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void send(String destination, byte[] payload, MimeType contentType) {
        messagingTemplate.send(destination, MessageBuilder
                .withPayload(payload)
                .setHeader(MessageHeaders.CONTENT_TYPE, contentType)
                .build());
    }

    @Override
    public void sendToSession(String userId, String sessionId, String destination,
                              byte[] payload, MimeType contentType) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setContentType(contentType);
        accessor.setLeaveMutable(true);

        // convertAndSendToUser 가 내부적으로 하는 것과 같은 목적지 조립.
        // 컨버터를 태우지 않기 위해 직접 send 한다.
        messagingTemplate.send(
                "/user/" + userId + destination,
                MessageBuilder.createMessage(payload, accessor.getMessageHeaders()));
    }
}
