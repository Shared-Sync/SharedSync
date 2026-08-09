package com.sharedsync.shared.auth;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * 앱이 이미 갖고 있는 {@link StompAccessValidator} 를 raw WebSocket 경로에서 재사용하기 위한 어댑터.
 *
 * 앱들의 validator 는 목적지 문자열을 정규식으로 받는 형태로 구현돼 있다(예: PlanRoomAccessValidator).
 * transport 를 바꾸면서 그 구현을 다시 쓰게 하지 않으려고, roomId 에서 STOMP 시절과 동일한 목적지
 * 문자열을 되만들어 넘긴다. 앱이 {@link SyncAccessValidator} 를 직접 구현하면 그쪽이 우선한다.
 */
@RequiredArgsConstructor
public class StompAccessValidatorAdapter implements SyncAccessValidator {

    private final List<StompAccessValidator> validators;

    @Override
    public void validate(String userId, String roomId, String channel) {
        // 편집 채널과 프레즌스 채널은 인가 정책이 같지만, supports() 가 둘 중 하나만 받는 구현이
        // 있을 수 있어 실제로 매칭되는 형태를 찾아 검증한다.
        String presenceDestination = "/topic/" + channel + "/" + roomId;
        String editDestination = "/topic/" + roomId;

        for (String destination : List.of(presenceDestination, editDestination)) {
            for (StompAccessValidator validator : validators) {
                if (validator.supports(destination)) {
                    validator.validate(userId, destination);
                    return;
                }
            }
        }
        // 아무 validator 도 이 룸을 다루지 않으면 STOMP 경로와 마찬가지로 통과시킨다.
    }
}
