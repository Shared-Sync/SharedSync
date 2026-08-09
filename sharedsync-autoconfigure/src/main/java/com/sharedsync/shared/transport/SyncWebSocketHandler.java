package com.sharedsync.shared.transport;

import java.nio.ByteBuffer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import com.sharedsync.shared.auth.SyncAccessValidator;
import com.sharedsync.shared.codec.ClientFrame;
import com.sharedsync.shared.codec.ProtoFrameDecoder;
import com.sharedsync.shared.codec.ProtoSyncCodec;
import com.sharedsync.shared.controller.SyncDispatcher;
import com.sharedsync.shared.listener.PresenceSessionManager;
import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.properties.SharedSyncAuthProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * raw WebSocket + protobuf 경로의 프레임 핸들러. STOMP 의 StompSubProtocolHandler 자리를 대신한다.
 *
 * STOMP 가 해주던 것 중 여기서 직접 해야 하는 것:
 * <ul>
 *   <li>세션 등록/해제 (STOMP: SessionConnect/DisconnectEvent -> SharedEventTracker)</li>
 *   <li>룸 입장 = 구독 (STOMP: SUBSCRIBE 프레임의 destination 파싱)</li>
 *   <li>인가 (STOMP: WsAuthChannelInterceptor + StompAccessValidator)</li>
 *   <li>세션 컨텍스트 주입 (STOMP: SimpAttributesContextHolder)</li>
 * </ul>
 *
 * userId 는 핸드셰이크 인터셉터가 세션 attribute 에 넣어둔 값을 쓴다. raw WS 에는 CONNECT 프레임이
 * 없어 토큰을 다시 받을 자리가 없으므로, 인증은 핸드셰이크가 유일한 관문이다.
 */
@Slf4j
@RequiredArgsConstructor
public class SyncWebSocketHandler extends BinaryWebSocketHandler {

    private static final String USER_ID = "userId";

    private final WebSocketSessionRegistry registry;
    private final ProtoFrameDecoder decoder;
    private final ProtoSyncCodec codec;
    private final SyncDispatcher dispatcher;
    private final PresenceSessionManager presenceSessionManager;
    private final PresenceRootResolver presenceRootResolver;
    private final SharedSyncAuthProperties authProperties;
    /** 앱이 제공하지 않으면 인가 없이 통과한다 (auth.enabled=false 데모 모드와 같은 취급). */
    private final ObjectProvider<SyncAccessValidator> accessValidator;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        registry.register(session);
        // 스키마 해시를 먼저 알린다. 클라이언트가 Join 에 실어 보낼 값을 여기서 맞춰볼 수 있다.
        send(session.getId(), codec.encodeHello());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String sessionId = session.getId();
        ClientFrame frame;
        try {
            frame = decoder.decode(toBytes(message.getPayload()));
        } catch (Exception e) {
            // 스키마 스큐거나 프레임이 깨진 것이다. 연결을 끊지는 않는다 — 클라이언트가
            // 재전송하거나 Join 부터 다시 할 수 있다.
            log.warn("[SharedSync] ClientFrame 파싱 실패 sessionId={}: {}", sessionId, e.getMessage());
            send(sessionId, codec.encodeError("MALFORMED_FRAME", "ClientFrame 파싱 실패"));
            return;
        }

        presenceSessionManager.handleHeartbeat(sessionId);

        if (frame instanceof ClientFrame.Join join) {
            handleJoin(session, join);
        } else if (frame instanceof ClientFrame.Edit edit) {
            handleEdit(session, edit);
        } else if (frame instanceof ClientFrame.Ping) {
            send(sessionId, codec.encodePong());
        } else if (frame instanceof ClientFrame.Unknown unknown) {
            log.debug("[SharedSync] 처리할 수 없는 프레임 sessionId={}: {}", sessionId, unknown.detail());
            send(sessionId, codec.encodeError("UNKNOWN_FRAME", unknown.detail()));
        }
    }

    private void handleJoin(WebSocketSession session, ClientFrame.Join join) throws Exception {
        String sessionId = session.getId();
        String roomId = join.roomId();

        if (roomId == null || roomId.isBlank()) {
            send(sessionId, codec.encodeError("INVALID_JOIN", "room_id 가 비어 있다"));
            return;
        }

        // 스키마가 다르면 필드 번호 해석이 달라져 조용히 엉뚱한 값이 들어간다. 여기서 끊는 편이 낫다.
        String serverHash = codec.getDescriptors().getSchemaHash();
        if (!serverHash.equals(join.schemaHash())) {
            log.warn("[SharedSync] 스키마 해시 불일치 sessionId={} client={} server={}",
                    sessionId, join.schemaHash(), serverHash);
            send(sessionId, codec.encodeError("SCHEMA_MISMATCH",
                    "클라이언트 스키마가 서버와 다르다. server=" + serverHash));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        String userId = userIdOf(session);
        if (authProperties.isEnabled() && userId == null) {
            send(sessionId, codec.encodeError("UNAUTHENTICATED", "핸드셰이크에서 사용자를 확인하지 못했다"));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        SyncAccessValidator validator = accessValidator.getIfAvailable();
        if (validator != null && authProperties.isEnabled()) {
            try {
                validator.validate(userId, roomId, presenceRootResolver.getChannel());
            } catch (Exception e) {
                log.info("[SharedSync] 룸 접근 거부 userId={} roomId={}: {}", userId, roomId, e.getMessage());
                send(sessionId, codec.encodeError("ACCESS_DENIED", "룸에 접근할 수 없다"));
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
        }

        registry.joinRoom(sessionId, roomId);
        // STOMP 의 SUBSCRIBE 이벤트와 같은 자리. 프레즌스 등록·캐시 초기화·초기 스냅샷 전송이 여기서 일어난다.
        presenceSessionManager.handleSubscribe(roomId, userId, sessionId);
    }

    private void handleEdit(WebSocketSession session, ClientFrame.Edit edit) throws Exception {
        String sessionId = session.getId();
        String roomId = registry.roomOf(sessionId);
        if (roomId == null) {
            send(sessionId, codec.encodeError("NOT_JOINED", "Join 이 먼저 필요하다"));
            return;
        }

        WebSocketSyncSessionContext.bind(sessionId);
        try {
            dispatcher.dispatch(roomId, edit);
        } catch (Exception e) {
            log.error("[SharedSync] 편집 처리 실패 sessionId={} roomId={}: {}", sessionId, roomId, e.getMessage(), e);
            send(sessionId, codec.encodeError("EDIT_FAILED", e.getMessage()));
        } finally {
            WebSocketSyncSessionContext.clear();
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        // 서버가 보낸 ping 에 대한 응답. 브라우저는 자동으로 돌려준다.
        presenceSessionManager.handleHeartbeat(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        registry.remove(sessionId);
        presenceSessionManager.handleDisconnect(userIdOf(session), sessionId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("[SharedSync] WS 전송 오류 sessionId={}: {}", session.getId(), exception.getMessage());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private String userIdOf(WebSocketSession session) {
        Object value = session.getAttributes().get(USER_ID);
        return value == null ? null : String.valueOf(value);
    }

    /** 등록된 세션(동시 전송 보호 래퍼)을 거쳐 보낸다. 원본 세션에 직접 쓰면 직렬화가 깨진다. */
    private void send(String sessionId, byte[] payload) {
        registry.sendTo(sessionId, payload);
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }
}
