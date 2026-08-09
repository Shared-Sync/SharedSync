package com.sharedsync.shared.transport;

import org.springframework.util.MimeType;

import lombok.RequiredArgsConstructor;

/**
 * raw WebSocket transport.
 *
 * STOMP 브로커의 구독 테이블 대신 {@link WebSocketSessionRegistry} 의 roomId -> 세션 맵을 쓴다.
 * 호출부는 여전히 destination 문자열을 넘기므로("/topic/{roomId}", "/topic/{channel}/{roomId}")
 * 여기서 마지막 세그먼트를 roomId 로 해석한다 — 두 형태 모두 마지막이 roomId 다.
 *
 * contentType 은 무시한다. raw WS 프레임에는 헤더가 없고, 이 transport 는 항상 바이너리 프레임으로
 * 내보낸다(codec=protobuf 강제). 대신 프레임 종류는 ServerFrame 의 oneof 가 구분한다.
 */
@RequiredArgsConstructor
public class WebSocketSyncTransport implements SyncTransport {

    private final WebSocketSessionRegistry registry;

    @Override
    public void send(String destination, byte[] payload, MimeType contentType) {
        String roomId = roomIdOf(destination);
        if (roomId == null) {
            return;
        }
        registry.broadcast(roomId, payload);
    }

    @Override
    public void sendToSession(String userId, String sessionId, String destination,
                              byte[] payload, MimeType contentType) {
        // 대상 세션이 다른 인스턴스에 붙어 있으면 registry 가 조용히 무시한다.
        // (Redis 팬아웃으로 모든 인스턴스가 같은 호출을 받으므로 하나만 실제로 보낸다)
        registry.sendTo(sessionId, payload);
    }

    static String roomIdOf(String destination) {
        if (destination == null || destination.isBlank()) {
            return null;
        }
        String trimmed = destination.endsWith("/")
                ? destination.substring(0, destination.length() - 1)
                : destination;
        int slash = trimmed.lastIndexOf('/');
        String last = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        return last.isEmpty() ? null : last;
    }
}
