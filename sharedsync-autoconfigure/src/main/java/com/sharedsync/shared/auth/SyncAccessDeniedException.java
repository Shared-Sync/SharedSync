package com.sharedsync.shared.auth;

/**
 * 룸/목적지 접근 거부.
 *
 * 예전에는 Spring Security 의 AccessDeniedException 을 던졌는데, 그 클래스 하나 때문에
 * spring-boot-starter-security 를 의존에 두고 있었다. 스타터는 클래스만 얹는 게 아니라
 * **시큐리티 자동설정을 켠다** — SharedSync 를 넣었다는 이유로 소비 앱의 모든 HTTP 엔드포인트에
 * 기본 인증이 걸릴 수 있다. 프레임워크가 앱의 보안 구성을 바꿔서는 안 된다.
 */
public class SyncAccessDeniedException extends RuntimeException {

    public SyncAccessDeniedException(String message) {
        super(message);
    }
}
