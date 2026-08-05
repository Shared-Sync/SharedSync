package com.sharedsync.shared.codec;

import org.springframework.util.MimeType;

/**
 * 동기화 페이로드의 wire 인코딩 경계.
 *
 * 인코딩은 publish 시점에 **한 번만** 수행하고 그 뒤로는 바이트로만 다룬다.
 * Redis 팬아웃 구간에서 페이로드가 다시 역직렬화되며 타입을 잃던 문제
 * (RedisSyncMessage.payload 가 Object 였을 때 LinkedHashMap 으로 퇴화)를 이 경계로 막는다.
 */
public interface SyncCodec {

    /**
     * 페이로드를 wire 바이트로 인코딩한다.
     */
    byte[] encode(Object payload);

    /**
     * wire 바이트를 지정한 타입으로 디코딩한다.
     */
    <T> T decode(byte[] data, Class<T> type);

    /**
     * 이 코덱이 만들어내는 바이트의 content-type.
     *
     * STOMP 프레임 헤더로 나가며, 바이너리/텍스트 프레임 결정에도 쓰인다
     * (StompSubProtocolHandler 는 application/octet-stream 일 때만 BinaryMessage 로 보낸다).
     */
    MimeType contentType();
}
