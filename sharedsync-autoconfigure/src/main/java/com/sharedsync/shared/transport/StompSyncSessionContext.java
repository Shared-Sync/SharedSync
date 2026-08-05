package com.sharedsync.shared.transport;

import org.springframework.messaging.simp.SimpAttributesContextHolder;

/**
 * STOMP 경로의 세션 컨텍스트. 기존 동작(SimpAttributesContextHolder 직접 조회)을 그대로 옮긴 것이라
 * 이 클래스 도입만으로는 관측 가능한 동작이 바뀌지 않는다.
 *
 * 빈 등록은 SharedSyncAutoConfig 가 @ConditionalOnMissingBean 으로 한다 —
 * WS transport 가 활성화되면 자기 구현으로 대체한다.
 */
public class StompSyncSessionContext implements SyncSessionContext {

    @Override
    public String currentSessionId() {
        try {
            return SimpAttributesContextHolder.currentAttributes().getSessionId();
        } catch (Exception e) {
            // STOMP 메시지 처리 스레드가 아니면 컨텍스트가 없다.
            return null;
        }
    }
}
