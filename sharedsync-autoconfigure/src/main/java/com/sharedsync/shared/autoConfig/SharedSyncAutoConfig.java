package com.sharedsync.shared.autoConfig;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedsync.shared.codec.JsonSyncCodec;
import com.sharedsync.shared.codec.ProtoSyncCodec;
import com.sharedsync.shared.codec.SyncDescriptors;
import com.sharedsync.shared.codec.SyncCodec;
import com.sharedsync.shared.config.RedisConfig;
import com.sharedsync.shared.config.RedisSyncConfig;
import com.sharedsync.shared.config.SharedSyncRawWebSocketConfig;
import com.sharedsync.shared.config.SharedWebSocketConfig;
import com.sharedsync.shared.controller.SyncSchemaController;
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
        // 조합 보정은 SharedSyncWebSocketProperties.normalize() 가 이미 끝냈다.
        // 여기서는 결정된 값을 그대로 따른다.
        if (props.isProtobuf()) {
            return new ProtoSyncCodec(new SyncDescriptors());
        }
        return new JsonSyncCodec(objectMapper);
    }

    /**
     * 생성된 wire 스키마를 그대로 내려주는 엔드포인트.
     *
     * 클라이언트는 이 바이트로 코드를 생성하고, 같은 바이트의 해시를 Join 에 실어 보낸다.
     * 앱이 만들어야 하는 것이 아니라 프레임워크가 제공한다 — 스키마는 프레임워크 생성물이고,
     * 앱은 자기 엔티티에 @CacheEntity 를 붙였을 뿐이다.
     */
    @Bean
    public RouterFunction<ServerResponse> sharedSyncSchemaRoutes(SyncCodec codec,
                                                                 SharedSyncWebSocketProperties props) {
        return new SyncSchemaController(codec, props).routes();
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
