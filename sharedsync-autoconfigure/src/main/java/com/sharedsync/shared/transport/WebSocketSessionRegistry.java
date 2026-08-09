package com.sharedsync.shared.transport;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import lombok.extern.slf4j.Slf4j;

/**
 * raw WebSocket 세션 레지스트리.
 *
 * STOMP 의 DefaultSubscriptionRegistry 를 대신한다. 이 앱에서 쓰는 목적지는 편집/프레즌스
 * 두 종류에 와일드카드가 없어서 roomId -> 세션 집합 맵 하나로 충분하다.
 *
 * 모든 세션은 ConcurrentWebSocketSessionDecorator 로 감싼다.
 * WebSocketSession.sendMessage() 는 동시 호출에 안전하지 않아서, 두 사용자가 동시에 편집하면
 * 프레임이 인터리빙되어 깨진다. STOMP 에서는 clientOutboundChannel 이 이 직렬화를 해줬다.
 */
@Slf4j
public class WebSocketSessionRegistry {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    /** sessionId -> 감싼 세션 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** sessionId -> roomId */
    private final Map<String, String> sessionRooms = new ConcurrentHashMap<>();

    /** roomId -> sessionId 집합 */
    private final Map<String, Set<String>> roomSessions = new ConcurrentHashMap<>();

    /**
     * 보내지 못한 프레임 수. 전송 실패는 "이 클라이언트만 편집을 못 받았다"는 뜻이라 화면이
     * 조용히 갈라진다. 세지 않으면 아무도 모른다.
     */
    private final AtomicLong sendFailures = new AtomicLong();

    /** 핸드셰이크 직후 등록. 아직 어느 룸에도 속하지 않는다 (Join 을 받아야 한다). */
    public WebSocketSession register(WebSocketSession raw) {
        WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(
                raw, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
        sessions.put(raw.getId(), guarded);
        return guarded;
    }

    /** Join 처리. 이미 다른 룸에 있었다면 옮긴다. */
    public void joinRoom(String sessionId, String roomId) {
        String previous = sessionRooms.put(sessionId, roomId);
        if (previous != null && !previous.equals(roomId)) {
            removeFromRoom(previous, sessionId);
        }
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    public String remove(String sessionId) {
        sessions.remove(sessionId);
        String roomId = sessionRooms.remove(sessionId);
        if (roomId != null) {
            removeFromRoom(roomId, sessionId);
        }
        return roomId;
    }

    private void removeFromRoom(String roomId, String sessionId) {
        Set<String> members = roomSessions.get(roomId);
        if (members != null) {
            members.remove(sessionId);
            if (members.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }
    }

    public String roomOf(String sessionId) {
        return sessionRooms.get(sessionId);
    }

    public WebSocketSession session(String sessionId) {
        return sessions.get(sessionId);
    }

    public Set<String> localSessionIds() {
        return sessions.keySet();
    }

    public long sendFailureCount() {
        return sendFailures.get();
    }

    public int roomCount() {
        return roomSessions.size();
    }

    public int sessionCount() {
        return sessions.size();
    }

    /** 룸의 모든 세션에 바이너리 프레임을 보낸다. */
    public void broadcast(String roomId, byte[] payload) {
        Set<String> members = roomSessions.get(roomId);
        if (members == null || members.isEmpty()) {
            return;
        }
        for (String sessionId : members) {
            sendTo(sessionId, payload);
        }
    }

    /** 특정 세션에만 보낸다. 이 인스턴스에 없으면 조용히 무시한다 (다른 인스턴스에 붙어 있다). */
    public void sendTo(String sessionId, byte[] payload) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new BinaryMessage(payload));
        } catch (IOException | IllegalStateException e) {
            // 이 세션만 이 편집을 못 받은 것이다. 다시 보내지 않으므로 화면이 갈라진 채로 남는다.
            sendFailures.incrementAndGet();
            log.warn("[SharedSync] WS 전송 실패 sessionId={} bytes={}: {}",
                    sessionId, payload.length, e.getMessage());
        }
    }

    /**
     * 열려 있는 모든 세션에 WebSocket ping 을 보낸다.
     *
     * 브라우저 JS 는 ping 프레임을 보낼 수 없으므로(WebSocket API 에 ping 메서드가 없다)
     * 서버->클라 방향 생존 확인은 이 경로가 유일하다. 브라우저는 자동으로 pong 을 돌려준다.
     */
    public void pingAll() {
        for (WebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(new PingMessage());
            } catch (IOException | IllegalStateException e) {
                log.debug("[SharedSync] WS ping 실패 sessionId={}: {}", session.getId(), e.getMessage());
            }
        }
    }
}
