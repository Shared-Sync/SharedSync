package com.sharedsync.shared.transport;

/**
 * raw WebSocket 경로의 세션 컨텍스트.
 *
 * STOMP 는 SimpAttributesContextHolder 가 메시지 처리 스레드에 세션을 실어주지만, raw WS 에는 그런
 * 컨텍스트가 없다. 핸들러가 프레임 처리 구간에서 직접 채운다. 채우지 않으면 undo 히스토리가
 * **예외 없이** 기록되지 않고 편집 메시지도 룸 인가에서 전부 무시된다.
 *
 * ThreadLocal 이 비어 있으면 STOMP 컨텍스트로 넘어간다. transport=both 에서 두 경로가 같은 빈을
 * 공유하기 때문이다 — 그래야 STOMP 세션과 raw WS 세션이 한 서버에서 동시에 동작한다.
 */
public class WebSocketSyncSessionContext implements SyncSessionContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** 프레임 처리 시작 시 핸들러가 호출한다. 반드시 finally 에서 {@link #clear()} 할 것. */
    public static void bind(String sessionId) {
        CURRENT.set(sessionId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    @Override
    public String currentSessionId() {
        String sessionId = CURRENT.get();
        if (sessionId != null) {
            return sessionId;
        }
        // transport=both 에서는 STOMP 세션도 같은 컨텍스트를 거쳐 온다. 여기서 null 을 돌려주면
        // STOMP 쪽 편집이 룸 인가에 실패해 **전부 무시**된다(예외 없이).
        try {
            return org.springframework.messaging.simp.SimpAttributesContextHolder
                    .currentAttributes().getSessionId();
        } catch (Exception e) {
            // STOMP 메시지 처리 스레드가 아니다. raw WS 전용 모드에서는 항상 이쪽이다.
            return null;
        }
    }
}
