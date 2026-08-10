package com.sharedsync.shared.autoConfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;

/**
 * transport 별 배선이 프로퍼티만으로 갈리는지 확인한다.
 *
 * 전체 컨텍스트를 띄우는 검증은 소비 앱(Backend-v2)에만 있었다. 프레임워크 쪽에서도 조건을
 * 잠가두지 않으면, 조건 하나를 잘못 건드렸을 때 소비 앱을 빌드해봐야 알게 된다.
 */
class TransportWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertiesOnlyConfig.class));

    @Test
    @DisplayName("기본값은 STOMP 이고 codec 은 json 이다")
    void defaultsToStompAndJson() {
        runner.run(context -> {
            SharedSyncWebSocketProperties props = context.getBean(SharedSyncWebSocketProperties.class);
            assertThat(props.isRawWebSocket()).isFalse();
            assertThat(props.isProtobuf()).isFalse();
            assertThat(props.isSockjs()).isTrue();
        });
    }

    @Test
    @DisplayName("transport=websocket 하나만 주면 codec 과 sockjs 는 프레임워크가 맞춘다")
    void rawWebSocketTransportDerivesCodecAndSockJs() {
        runner.withPropertyValues("sharedsync.websocket.transport=websocket").run(context -> {
            SharedSyncWebSocketProperties props = context.getBean(SharedSyncWebSocketProperties.class);
            assertThat(props.isRawWebSocket()).isTrue();
            assertThat(props.isProtobuf())
                    .as("raw WS 프레임은 ClientFrame/ServerFrame 이라 JSON 코덱으로는 해석할 수 없다")
                    .isTrue();
            assertThat(props.isSockjs())
                    .as("SockJS 세션은 바이너리 프레임을 보내지 못해 바이트가 조용히 손상된다")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("codec=protobuf 만 켜도 SockJS 는 꺼진다 (STOMP + 바이너리 조합)")
    void protobufCodecDisablesSockJs() {
        runner.withPropertyValues("sharedsync.websocket.codec=protobuf").run(context -> {
            SharedSyncWebSocketProperties props = context.getBean(SharedSyncWebSocketProperties.class);
            assertThat(props.isRawWebSocket()).isFalse();
            assertThat(props.isSockjs()).isFalse();
        });
    }

    @Test
    @DisplayName("스키마 경로 기본값은 endpoint 하위다 (앱의 화이트리스트를 다시 고치지 않도록)")
    void schemaPathDefaultsUnderEndpoint() {
        runner.withPropertyValues("sharedsync.websocket.endpoint=/ws").run(context -> {
            SharedSyncWebSocketProperties props = context.getBean(SharedSyncWebSocketProperties.class);
            assertThat(props.getSchemaPath()).isEqualTo("/ws/schema.proto");
        });
    }

    /**
     * 프로퍼티 보정만 검증한다. 전체 자동 설정을 띄우려면 앱의 생성 코드(sharedsync.*)와 Redis 가
     * 필요해서, 그 조합은 소비 앱 통합 테스트가 담당한다.
     */
    @Configuration(proxyBeanMethods = false)
    static class PropertiesOnlyConfig {

        @Bean
        SharedSyncWebSocketProperties sharedSyncWebSocketProperties(
                org.springframework.core.env.Environment environment) {
            SharedSyncWebSocketProperties props = new SharedSyncWebSocketProperties();
            props.setTransport(environment.getProperty("sharedsync.websocket.transport", "stomp"));
            props.setCodec(environment.getProperty("sharedsync.websocket.codec", "json"));
            props.setSockjs(Boolean.parseBoolean(
                    environment.getProperty("sharedsync.websocket.sockjs", "true")));
            props.setEndpoint(environment.getProperty("sharedsync.websocket.endpoint", "/ws-sharedsync"));
            props.setSchemaPath(environment.getProperty("sharedsync.websocket.schema-path", ""));
            props.normalize();
            return props;
        }
    }
}
