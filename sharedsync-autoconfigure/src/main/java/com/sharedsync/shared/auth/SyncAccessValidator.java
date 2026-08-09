package com.sharedsync.shared.auth;

/**
 * 룸 접근 권한 검사. transport 중립이다.
 *
 * 기존 StompAccessValidator 는 destination 문자열("/topic/plan-presence/{uuid}")을 받아
 * 정규식으로 파싱했는데, raw WebSocket 에는 destination 개념이 없다. roomId 를 직접 받는다.
 *
 * 거부하려면 예외를 던진다.
 */
public interface SyncAccessValidator {

    /**
     * @param userId  인증된 사용자 ID
     * @param roomId  입장하려는 룸 ID
     * @param channel 프레즌스 채널명. 편집 채널이면 null.
     */
    void validate(String userId, String roomId, String channel);
}
