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
