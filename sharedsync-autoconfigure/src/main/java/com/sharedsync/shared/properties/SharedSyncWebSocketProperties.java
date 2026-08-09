package com.sharedsync.shared.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "sharedsync.websocket")
public class SharedSyncWebSocketProperties {

    /**
     * WebSocket endpoint path
     * 예: /ws-plan
     */
    private String endpoint = "/ws-sharedsync";

    /**
     * Allowed Origins
     */
    private List<String> allowedOrigins = List.of("*");

    /**
     * wire 페이로드 인코딩. json(기본, 현행) | protobuf
     */
    private String codec = "json";

    /**
     * 전송 계층. stomp(기본, 현행) | websocket(raw WebSocket + 바이너리 프레임)
     *
     * websocket 은 codec=protobuf 를 전제한다. 프레임 자체가 ClientFrame/ServerFrame 이라
     * JSON 코덱으로는 인바운드를 해석할 수 없다 (기동 시 검증한다).
     */
    private String transport = "stomp";

    /**
     * raw WebSocket 모드에서 서버가 보내는 ping 주기(초). 0 이면 보내지 않는다.
     *
     * 브라우저 JS 는 ping 프레임을 보낼 수 없어서(WebSocket API 에 메서드가 없다) 유휴 연결이
     * 프록시에 끊기는 것을 막으려면 서버가 먼저 보내야 한다. STOMP 에서는 하트비트가 이 역할을 했다.
     */
    private int pingInterval = 25;

    /**
     * SockJS 폴백 사용 여부.
     *
     * 바이너리 codec 과는 함께 쓸 수 없다. StompSubProtocolHandler 는 SockJS 세션이면
     * BinaryMessage 를 보내지 않고 바이트를 TextMessage 로 UTF-8 디코딩해 **예외 없이 손상**시킨다.
     * 그래서 기동 시 조합을 검증해 즉시 실패시킨다.
     */
    private boolean sockjs = true;

    /**
     * Redis Sync Settings
     */
    private RedisSync redisSync = new RedisSync();

    @Getter
    @Setter
    public static class RedisSync {
        /**
         * Enable Redis Pub/Sub for WebSocket synchronization across multiple servers
         */
        private boolean enabled = false;

        /**
         * Redis channel name for synchronization
         */
        private String channel = "sharedsync:websocket:sync";
    }
}
