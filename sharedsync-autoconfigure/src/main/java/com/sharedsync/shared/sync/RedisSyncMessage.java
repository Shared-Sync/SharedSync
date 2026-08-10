package com.sharedsync.shared.sync;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 인스턴스 간 WebSocket 팬아웃 메시지.
 *
 * payload 는 codec 이 publish 시점에 인코딩한 바이트다. 예전처럼 Object 로 두면 Redis 를
 * 왕복하며 LinkedHashMap 으로 퇴화해 수신 인스턴스에서는 원래 타입을 잃는다.
 * byte[] 는 Jackson 이 base64 로 왕복시키므로 바이트가 그대로 복원된다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedisSyncMessage {

    private String destination;

    private byte[] payload;

    /** payload 의 content-type (예: application/json, application/octet-stream) */
    private String contentType;

    /**
     * 특정 세션 한 곳에만 보내야 하는 메시지면 대상 세션 ID. 브로드캐스트면 null.
     * 세션이 어느 인스턴스에 붙어 있든 도달하도록 이 값을 Redis 에 함께 실어 보낸다.
     */
    private String targetSessionId;

    /** targetSessionId 가 있을 때 대상 사용자 ID. */
    private String targetUserId;

    /**
     * 이 바이트를 만든 채널의 이름(stomp/websocket).
     *
     * 수신 인스턴스는 같은 이름의 채널로만 내보내야 한다. 바이트가 그 채널의 codec 으로
     * 인코딩돼 있어서, 다른 채널로 보내면 JSON 을 기대하는 클라이언트에 protobuf 가 간다.
     * 채널이 하나뿐이던 시절의 메시지(값 없음)는 모든 채널로 보낸다 — 롤링 배포 중 구버전
     * 인스턴스가 발행한 메시지가 그렇다.
     */
    private String channel;
}
