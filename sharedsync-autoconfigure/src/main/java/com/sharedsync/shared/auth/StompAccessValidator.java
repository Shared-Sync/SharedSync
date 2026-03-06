package com.sharedsync.shared.auth;

/**
 * STOMP 목적지에 대한 접근 권한 검증 전략.
 */
public interface StompAccessValidator {

    /**
     * 해당 검증기가 이 목적지를 처리할 수 있는지 여부.
     */
    boolean supports(String destination);

    /**
     * 목적지에 대한 접근 권한을 검증한다. 검증 실패 시 예외를 던진다.
     */
    void validate(String userId, String destination);
}
