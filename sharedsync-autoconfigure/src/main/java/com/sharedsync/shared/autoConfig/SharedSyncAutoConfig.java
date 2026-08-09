package com.sharedsync.shared.autoConfig;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import com.sharedsync.shared.config.SharedSyncRawWebSocketConfig;
import com.sharedsync.shared.config.SharedWebSocketConfig;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;
import com.sharedsync.shared.transport.StompSyncSessionContext;
import com.sharedsync.shared.transport.SyncSessionContext;

@Configuration
@EnableConfigurationProperties(SharedSyncWebSocketProperties.class)
@EnableScheduling
@Import({RedisConfig.class, RedisSyncConfig.class, SharedWebSocketConfig.class,
        SharedSyncRawWebSocketConfig.class})
@ComponentScan(basePackages = {"sharedsync", "com.sharedsync"})
public class SharedSyncAutoConfig {

    /**
     * 기본 wire 코덱. 애플리케이션이 자체 SyncCodec 빈을 등록하면 그쪽이 우선한다.
     */
    @Bean
    @ConditionalOnMissingBean(SyncCodec.class)
    public SyncCodec syncCodec(ObjectMapper objectMapper, SharedSyncWebSocketProperties props) {
        boolean stompTransport = !"websocket".equalsIgnoreCase(props.getTransport());

        if ("protobuf".equalsIgnoreCase(props.getCodec())) {
            // SockJS 세션에는 BinaryMessage 를 보낼 수 없다. 그대로 두면 바이트가 UTF-8 로
            // 디코딩되어 예외 없이 손상되므로, 여기서 명확히 실패시킨다.
            // raw WS 모드에는 SockJS 폴백 자체가 없으므로 이 조합 검사도 필요 없다.
            if (stompTransport && props.isSockjs()) {
                throw new IllegalStateException(
                        "sharedsync.websocket.codec=protobuf 는 SockJS 와 함께 쓸 수 없다. "
                                + "sharedsync.websocket.sockjs=false 로 둘 것. "
                                + "(SockJS 세션은 바이너리 프레임을 보내지 못해 페이로드가 조용히 손상된다)");
            }
            return new ProtoSyncCodec(new SyncDescriptors());
        }
        if (!stompTransport) {
            throw new IllegalStateException(
                    "sharedsync.websocket.transport=websocket 은 codec=protobuf 를 전제한다. "
                            + "sharedsync.websocket.codec=protobuf 로 둘 것.");
        }
        return new JsonSyncCodec(objectMapper);
    }

    /**
     * STOMP 세션 컨텍스트. raw WS 모드에서는 SharedSyncRawWebSocketConfig 가 자기 구현을 등록한다
     * (@ConditionalOnMissingBean 대신 프로퍼티로 갈라 두 설정의 평가 순서에 의존하지 않게 한다).
     */
    @Bean
    @ConditionalOnProperty(prefix = "sharedsync.websocket", name = "transport",
            havingValue = "stomp", matchIfMissing = true)
    @ConditionalOnMissingBean(SyncSessionContext.class)
    public SyncSessionContext stompSyncSessionContext() {
        return new StompSyncSessionContext();
    }
}
