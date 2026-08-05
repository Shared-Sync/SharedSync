package com.sharedsync.shared.autoConfig;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedsync.shared.codec.JsonSyncCodec;
import com.sharedsync.shared.codec.ProtoSyncCodec;
import com.sharedsync.shared.codec.SyncDescriptors;
import com.sharedsync.shared.codec.SyncCodec;
import com.sharedsync.shared.config.RedisConfig;
import com.sharedsync.shared.config.RedisSyncConfig;
import com.sharedsync.shared.config.SharedWebSocketConfig;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;
import com.sharedsync.shared.transport.StompSyncSessionContext;
import com.sharedsync.shared.transport.SyncSessionContext;

@Configuration
@EnableConfigurationProperties(SharedSyncWebSocketProperties.class)
@EnableScheduling
@Import({RedisConfig.class, RedisSyncConfig.class, SharedWebSocketConfig.class})
@ComponentScan(basePackages = {"sharedsync", "com.sharedsync"})
public class SharedSyncAutoConfig {

    /**
     * 기본 wire 코덱. 애플리케이션이 자체 SyncCodec 빈을 등록하면 그쪽이 우선한다.
     */
    @Bean
    @ConditionalOnMissingBean(SyncCodec.class)
    public SyncCodec syncCodec(ObjectMapper objectMapper, SharedSyncWebSocketProperties props) {
        if ("protobuf".equalsIgnoreCase(props.getCodec())) {
            // SockJS 세션에는 BinaryMessage 를 보낼 수 없다. 그대로 두면 바이트가 UTF-8 로
            // 디코딩되어 예외 없이 손상되므로, 여기서 명확히 실패시킨다.
            if (props.isSockjs()) {
                throw new IllegalStateException(
                        "sharedsync.websocket.codec=protobuf 는 SockJS 와 함께 쓸 수 없다. "
                                + "sharedsync.websocket.sockjs=false 로 둘 것. "
                                + "(SockJS 세션은 바이너리 프레임을 보내지 못해 페이로드가 조용히 손상된다)");
            }
            return new ProtoSyncCodec(new SyncDescriptors());
        }
        return new JsonSyncCodec(objectMapper);
    }

    /**
     * 기본 세션 컨텍스트(STOMP). WS transport 가 활성화되면 자기 구현으로 대체한다.
     */
    @Bean
    @ConditionalOnMissingBean(SyncSessionContext.class)
    public SyncSessionContext stompSyncSessionContext() {
        return new StompSyncSessionContext();
    }
}
