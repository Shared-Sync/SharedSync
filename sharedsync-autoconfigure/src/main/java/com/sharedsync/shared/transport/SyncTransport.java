package com.sharedsync.shared.transport;

import org.springframework.util.MimeType;

/**
 * 이미 인코딩된 동기화 메시지를 실제로 클라이언트에 내보내는 경계.
 *
 * 페이로드는 {@link com.sharedsync.shared.codec.SyncCodec} 이 publish 시점에 한 번 인코딩한
 * 바이트다. transport 는 다시 직렬화하지 않는다 — Redis 팬아웃을 거쳐도 바이트가 그대로 보존된다.
 *
 * destination 문자열("/topic/{roomId}" 등)은 구현이 어떻게 해석하든 호출부에는 그대로 노출된다.
 */
public interface SyncTransport {

    /**
     * 해당 destination 을 구독 중인 모든 세션에 브로드캐스트한다.
     */
    void send(String destination, byte[] payload, MimeType contentType);

    /**
     * 특정 사용자의 특정 세션 하나에만 전송한다 (프레즌스 초기 스냅샷 등).
     */
    void sendToSession(String userId, String sessionId, String destination, byte[] payload, MimeType contentType);
}
