package com.sharedsync.shared.transport;

/**
 * raw WebSocket 경로의 세션 컨텍스트.
 *
 * STOMP 는 SimpAttributesContextHolder 가 메시지 처리 스레드에 세션을 실어주지만, raw WS 에는 그런
 * 컨텍스트가 없다. 핸들러가 프레임 처리 구간에서 직접 채운다. 채우지 않으면 undo 히스토리가
 * **예외 없이** 기록되지 않고 편집 메시지도 룸 인가에서 전부 무시된다.
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
        return CURRENT.get();
    }
}
