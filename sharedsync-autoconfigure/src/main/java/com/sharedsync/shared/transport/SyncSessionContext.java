package com.sharedsync.shared.transport;

/**
 * 현재 처리 중인 동기화 메시지가 어느 세션에서 왔는지 알려주는 경계.
 *
 * 예전에는 이 정보를 SimpAttributesContextHolder 에서 직접 꺼냈는데, 그건 STOMP 전용 컨텍스트라
 * raw WebSocket transport 에서는 존재하지 않는다. 그대로 두면 **예외 없이 조용히** 다음이 깨진다:
 *
 * <ul>
 *   <li>HistoryService.record() 가 sessionId == null 이라 早期 return → undo 히스토리 미기록.
 *       히스토리 키가 "history:undo:{rootId}:{sessionId}" 로 세션별이라 성립 자체가 안 된다.</li>
 *   <li>생성된 컨트롤러가 getRootIdBySessionId(null) 로 룸 인가에 실패 → 모든 편집 메시지 무시.</li>
 * </ul>
 */
public interface SyncSessionContext {

    /**
     * 현재 메시지의 세션 ID. 알 수 없으면 null.
     */
    String currentSessionId();
}
