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
