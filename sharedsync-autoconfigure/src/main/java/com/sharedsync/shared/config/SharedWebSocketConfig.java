package com.sharedsync.shared.config;

import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.sharedsync.shared.listener.PresenceSessionManager;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;

@Configuration
@EnableWebSocketMessageBroker
public class SharedWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final SharedSyncWebSocketProperties props;
    private final List<HandshakeInterceptor> handshakeInterceptors;
    private final PresenceSessionManager presenceSessionManager;

    public SharedWebSocketConfig(
            SharedSyncWebSocketProperties props,
            ObjectProvider<List<HandshakeInterceptor>> interceptors,
            @Lazy PresenceSessionManager presenceSessionManager
    ) {
        this.props = props;
        this.handshakeInterceptors =
                interceptors.getIfAvailable(Collections::emptyList);
        this.presenceSessionManager = presenceSessionManager;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {

        registry.addEndpoint(props.getEndpoint())
                .setAllowedOrigins(props.getAllowedOrigins().toArray(new String[0]))
                .addInterceptors(handshakeInterceptors.toArray(new HandshakeInterceptor[0]))
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
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
