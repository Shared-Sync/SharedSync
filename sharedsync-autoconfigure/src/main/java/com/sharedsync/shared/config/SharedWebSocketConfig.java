package com.sharedsync.shared.config;

import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.sharedsync.shared.auth.AuthenticationTokenResolver;
import com.sharedsync.shared.auth.StompAccessValidator;
import com.sharedsync.shared.auth.WsAuthChannelInterceptor;
import com.sharedsync.shared.listener.SharedEventTracker;
import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.properties.SharedSyncAuthProperties;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;
import com.sharedsync.shared.listener.PresenceSessionManager;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedsync.shared.codec.JsonSyncCodec;
import com.sharedsync.shared.codec.ProtoSyncCodec;
import com.sharedsync.shared.codec.SyncCodec;
import com.sharedsync.shared.codec.SyncDescriptors;
import com.sharedsync.shared.sync.SyncChannel;
import com.sharedsync.shared.transport.StompSyncTransport;

/**
 * STOMP 전송 계층(기본). {@code sharedsync.websocket.transport=websocket} 이면 통째로 꺼지고
 * {@link SharedSyncRawWebSocketConfig} 가 같은 endpoint 를 raw WebSocket 으로 잡는다.
 */
@lombok.extern.slf4j.Slf4j
@Configuration
@EnableWebSocketMessageBroker
@Conditional(TransportCondition.Stomp.class)
public class SharedWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final SharedSyncWebSocketProperties props;
    private final List<HandshakeInterceptor> handshakeInterceptors;
    private final PresenceSessionManager presenceSessionManager;
    private final ObjectProvider<WsAuthChannelInterceptor> authInterceptor;

    public SharedWebSocketConfig(
            SharedSyncWebSocketProperties props,
            ObjectProvider<List<HandshakeInterceptor>> interceptors,
            @Lazy PresenceSessionManager presenceSessionManager,
            ObjectProvider<WsAuthChannelInterceptor> authInterceptor
    ) {
        this.props = props;
        this.handshakeInterceptors =
                interceptors.getIfAvailable(Collections::emptyList);
        this.presenceSessionManager = presenceSessionManager;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {

        var endpoint = registry.addEndpoint(props.getEndpoint())
                .setAllowedOrigins(props.getAllowedOrigins().toArray(new String[0]))
                .addInterceptors(handshakeInterceptors.toArray(new HandshakeInterceptor[0]));

        // SockJS 는 텍스트 프레임만 보낼 수 있어 바이너리 codec 과 함께 쓸 수 없다.
        // (조합 검증은 SharedSyncAutoConfig 가 기동 시점에 한다)
        if (props.isSockjs()) {
            endpoint.withSockJS();
        }
        log.info("[SharedSync] STOMP 활성화: endpoint={} sockjs={} allowed-origins={}",
                props.getEndpoint(), props.isSockjs(), props.getAllowedOrigins());
    }

    /**
     * STOMP transport. raw WS 모드에서는 SimpMessagingTemplate 자체가 없으므로 이 설정과 함께 꺼진다.
     */
    @Bean
    public SyncChannel stompSyncChannel(SimpMessagingTemplate messagingTemplate,
                                        ObjectMapper objectMapper,
                                        SharedSyncWebSocketProperties props) {
        // both 모드에서 STOMP 채널은 항상 JSON 이다. 구버전 클라이언트를 그대로 받는 것이 목적이라
        // 이쪽 wire 를 바꾸면 의미가 없다.
        SyncCodec codec = props.isBoth() || !props.isProtobuf()
                ? new JsonSyncCodec(objectMapper)
                : new ProtoSyncCodec(new SyncDescriptors());
        return new SyncChannel(SyncChannel.STOMP, new StompSyncTransport(messagingTemplate), codec);
    }

    /**
     * STOMP 프레임 단의 인증·인가. raw WebSocket 에는 CONNECT/SUBSCRIBE/SEND 프레임이 없어
     * 이 인터셉터가 걸릴 자리도 없다 — 그쪽은 핸드셰이크와 Join 에서 같은 일을 한다.
     */
    @Bean
    public WsAuthChannelInterceptor wsAuthChannelInterceptor(
            AuthenticationTokenResolver tokenResolver,
            ObjectProvider<List<StompAccessValidator>> accessValidators,
            SharedSyncAuthProperties authProperties) {
        return new WsAuthChannelInterceptor(tokenResolver,
                accessValidators.getIfAvailable(Collections::emptyList), authProperties);
    }

    /**
     * STOMP SUBSCRIBE/DISCONNECT 이벤트를 프레즌스로 옮기는 리스너.
     * raw WebSocket 모드에서는 이 이벤트 자체가 발생하지 않으므로 등록하지 않는다.
     */
    @Bean
    public SharedEventTracker sharedEventTracker(
            @Lazy PresenceSessionManager presenceSessionManager,
            PresenceRootResolver presenceRootResolver,
            SharedSyncPresenceProperties presenceProperties) {
        return new SharedEventTracker(presenceSessionManager, presenceRootResolver, presenceProperties);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 인증·인가. 예전에는 이 등록을 프레임워크가 하지 않아 WsAuthChannelInterceptor 가 빈으로만
        // 존재하고 한 번도 호출되지 않았고, 앱이 자기 설정으로 직접 걸어줘야 했다.
        WsAuthChannelInterceptor auth = authInterceptor.getIfAvailable();
        if (auth != null) {
            registration.interceptors(auth);
        }

        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && accessor.getSessionId() != null) {
                    // 클라이언트로부터 어떤 메시지(SEND, SUBSCRIBE, HEARTBEAT 등)가 오면 세션 생존 신고
                    presenceSessionManager.handleHeartbeat(accessor.getSessionId());
                }
                return message;
            }
        });
    }

}
