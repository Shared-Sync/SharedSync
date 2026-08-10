package com.sharedsync.shared.transport;

import java.nio.ByteBuffer;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import com.sharedsync.shared.auth.SyncAccessValidator;
import com.sharedsync.shared.codec.ClientFrame;
import com.sharedsync.shared.codec.ProtoFrameDecoder;
import com.sharedsync.shared.codec.ProtoSyncCodec;
import com.sharedsync.shared.controller.SyncDispatcher;
import com.sharedsync.shared.listener.PresenceSessionManager;
import com.sharedsync.shared.metrics.SyncMetrics;
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
public class SyncWebSocketHandler extends BinaryWebSocketHandler implements SubProtocolCapable {

    private static final String USER_ID = "userId";

    /** 클라이언트가 토큰과 함께 제시하는 프로토콜. 서버는 이쪽만 골라 응답한다. */
    private static final String SUB_PROTOCOL = "sharedsync.v1";

    private final WebSocketSessionRegistry registry;
    private final ProtoFrameDecoder decoder;
    private final ProtoSyncCodec codec;
    private final SyncDispatcher dispatcher;
    private final PresenceSessionManager presenceSessionManager;
    private final PresenceRootResolver presenceRootResolver;
    private final SharedSyncAuthProperties authProperties;
    private final SyncFrameExecutor frameExecutor;
    private final SyncMetrics metrics;
    /** 앱이 제공하지 않으면 인가 없이 통과한다 (auth.enabled=false 데모 모드와 같은 취급). */
    private final ObjectProvider<SyncAccessValidator> accessValidator;

    @Override
    public List<String> getSubProtocols() {
        // 이 목록을 비워두면 클라이언트가 제시한 프로토콜 중 아무것도 고르지 못해 브라우저가
        // 핸드셰이크를 실패로 처리한다 — 토큰을 프로토콜로 보내는 순간 연결 자체가 끊긴다.
        return List.of(SUB_PROTOCOL);
    }

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
            metrics.frame("malformed");
            sendError(sessionId, SyncErrorCode.MALFORMED_FRAME, "ClientFrame 파싱 실패");
            return;
        }

        // ping 은 상태를 건드리지 않으니 읽기 스레드에서 바로 답한다. 오히려 이게 하트비트의 목적에 맞다 —
        // 편집 큐가 밀려 있어도 생존 확인은 즉시 돌아가야 한다.
        if (frame instanceof ClientFrame.Ping) {
            metrics.frame("ping");
            presenceSessionManager.handleHeartbeat(sessionId);
            send(sessionId, codec.encodePong());
            return;
        }

        // 나머지는 DB·Redis 를 건드린다. 컨테이너의 읽기 스레드에서 하면 그 소켓이 통째로 막힌다.
        boolean accepted = frameExecutor.submit(sessionId, () -> process(session, frame));
        if (!accepted) {
            // 큐 한도 초과. 조용히 버리면 클라이언트는 편집이 반영된 줄 안다.
            metrics.rejected();
            sendError(sessionId, SyncErrorCode.BACKPRESSURE, "처리 대기열이 가득 찼다. 잠시 후 재시도할 것");
        }
    }

    /** 프레임 실행기 스레드에서 돈다. 세션별 순서는 실행기가 보장한다. */
    private void process(WebSocketSession session, ClientFrame frame) {
        String sessionId = session.getId();
        try {
            presenceSessionManager.handleHeartbeat(sessionId);

            if (frame instanceof ClientFrame.Join join) {
                metrics.frame("join");
                handleJoin(session, join);
            } else if (frame instanceof ClientFrame.Edit edit) {
                metrics.frame("edit");
                handleEdit(session, edit);
            } else if (frame instanceof ClientFrame.Unknown unknown) {
                metrics.frame("unknown");
                log.debug("[SharedSync] 처리할 수 없는 프레임 sessionId={}: {}", sessionId, unknown.detail());
                sendError(sessionId, SyncErrorCode.UNKNOWN_FRAME, unknown.detail());
            }
        } catch (Exception e) {
            log.error("[SharedSync] 프레임 처리 실패 sessionId={}: {}", sessionId, e.getMessage(), e);
            sendError(sessionId, SyncErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    private void handleJoin(WebSocketSession session, ClientFrame.Join join) {
        String sessionId = session.getId();
        String roomId = join.roomId();

        if (roomId == null || roomId.isBlank()) {
            sendError(sessionId, SyncErrorCode.INVALID_JOIN, "room_id 가 비어 있다");
            return;
        }

        // 스키마가 다르면 필드 번호 해석이 달라져 조용히 엉뚱한 값이 들어간다. 여기서 끊는 편이 낫다.
        String serverHash = codec.getDescriptors().getSchemaHash();
        if (!serverHash.equals(join.schemaHash())) {
            log.warn("[SharedSync] 스키마 해시 불일치 sessionId={} client={} server={}",
                    sessionId, join.schemaHash(), serverHash);
            sendError(sessionId, SyncErrorCode.SCHEMA_MISMATCH, "클라이언트 스키마가 서버와 다르다. server=" + serverHash);
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        String userId = userIdOf(session);
        if (authProperties.isEnabled() && userId == null) {
            sendError(sessionId, SyncErrorCode.UNAUTHENTICATED, "핸드셰이크에서 사용자를 확인하지 못했다");
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        SyncAccessValidator validator = accessValidator.getIfAvailable();
        if (validator != null && authProperties.isEnabled()) {
            try {
                validator.validate(userId, roomId, presenceRootResolver.getChannel());
            } catch (Exception e) {
                log.info("[SharedSync] 룸 접근 거부 userId={} roomId={}: {}", userId, roomId, e.getMessage());
                sendError(sessionId, SyncErrorCode.ACCESS_DENIED, "룸에 접근할 수 없다");
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                return;
            }
        }

        registry.joinRoom(sessionId, roomId);
        // STOMP 의 SUBSCRIBE 이벤트와 같은 자리. 프레즌스 등록·캐시 초기화·초기 스냅샷 전송이 여기서 일어난다.
        presenceSessionManager.handleSubscribe(roomId, userId, sessionId);
    }

    private void handleEdit(WebSocketSession session, ClientFrame.Edit edit) {
        String sessionId = session.getId();
        String roomId = registry.roomOf(sessionId);
        if (roomId == null) {
            sendError(sessionId, SyncErrorCode.NOT_JOINED, "Join 이 먼저 필요하다");
            return;
        }

        // ThreadLocal 은 실행기 스레드에 묶인다. 프레임마다 bind/clear 하므로 스레드가 재사용돼도
        // 이전 세션의 ID 가 남지 않는다.
        WebSocketSyncSessionContext.bind(sessionId);
        try {
            dispatcher.dispatch(roomId, edit);
        } catch (Exception e) {
            log.error("[SharedSync] 편집 처리 실패 sessionId={} roomId={}: {}", sessionId, roomId, e.getMessage(), e);
            sendError(sessionId, SyncErrorCode.EDIT_FAILED, e.getMessage());
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
        // 남아 있던 편집 프레임은 버린다 — 결과를 보낼 곳이 이미 없다. 퇴장 처리(프레즌스·히스토리
        // 정리)는 그 뒤에 실행기에서 돈다. Redis 왕복이라 컨테이너 스레드에서 하면 안 된다.
        frameExecutor.terminate(sessionId, () ->
                presenceSessionManager.handleDisconnect(userIdOf(session), sessionId));
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("[SharedSync] 세션 종료 실패 sessionId={}: {}", session.getId(), e.getMessage());
        }
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

    /** 에러 프레임은 한 곳으로 모은다 — 코드별 카운터를 빠뜨리지 않기 위해서다. */
    private void sendError(String sessionId, String code, String message) {
        metrics.error(code);
        send(sessionId, codec.encodeError(code, message));
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
