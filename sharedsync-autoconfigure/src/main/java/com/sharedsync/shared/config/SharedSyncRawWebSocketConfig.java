package com.sharedsync.shared.config;

import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.sharedsync.shared.auth.StompAccessValidator;
import com.sharedsync.shared.auth.StompAccessValidatorAdapter;
import com.sharedsync.shared.auth.SyncAccessValidator;
import com.sharedsync.shared.codec.ProtoFrameDecoder;
import com.sharedsync.shared.codec.ProtoSyncCodec;
import com.sharedsync.shared.codec.SyncCodec;
import com.sharedsync.shared.controller.SyncDispatcher;
import com.sharedsync.shared.listener.PresenceSessionManager;
import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.properties.SharedSyncAuthProperties;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;
import com.sharedsync.shared.transport.SyncFrameExecutor;
import com.sharedsync.shared.transport.SyncWebSocketHandler;
import com.sharedsync.shared.transport.SyncSessionContext;
import com.sharedsync.shared.transport.WebSocketPingScheduler;
import com.sharedsync.shared.transport.WebSocketSessionRegistry;
import com.sharedsync.shared.transport.WebSocketSyncSessionContext;
import com.sharedsync.shared.transport.WebSocketSyncTransport;
import com.sharedsync.shared.transport.SyncTransport;

/**
 * raw WebSocket 전송 계층. {@code sharedsync.websocket.transport=websocket} 일 때만 활성화되며,
 * 이때 STOMP 쪽 {@link SharedWebSocketConfig} 는 반대 조건으로 꺼진다 — 같은 endpoint 경로를
 * 두 핸들러가 잡으면 안 되고, 브로커/컨버터 체인도 통째로 필요 없어진다.
 */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(prefix = "sharedsync.websocket", name = "transport", havingValue = "websocket")
public class SharedSyncRawWebSocketConfig implements WebSocketConfigurer {

    private final SharedSyncWebSocketProperties props;
    private final List<HandshakeInterceptor> handshakeInterceptors;
    private final SyncWebSocketHandler handler;

    public SharedSyncRawWebSocketConfig(
            SharedSyncWebSocketProperties props,
            ObjectProvider<List<HandshakeInterceptor>> interceptors,
            @Lazy SyncWebSocketHandler handler
    ) {
        this.props = props;
        this.handshakeInterceptors = interceptors.getIfAvailable(Collections::emptyList);
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, props.getEndpoint())
                .setAllowedOrigins(props.getAllowedOrigins().toArray(new String[0]))
                .addInterceptors(handshakeInterceptors.toArray(new HandshakeInterceptor[0]));
        // SockJS 는 붙이지 않는다. 텍스트 프레임만 보낼 수 있어 바이너리 wire 와 양립하지 않는다.
    }

    /**
     * 인바운드 프레임 버퍼. 컨테이너 기본값(8KB)을 넘는 편집이 오면 프레임이 쪼개지는데 핸들러는
     * 부분 메시지를 조립하지 않는다 — STOMP 에서는 브로커가 해주던 일이라 앱이 알 필요가 없었다.
     */
    @Bean
    @ConditionalOnMissingBean(ServletServerContainerFactoryBean.class)
    public ServletServerContainerFactoryBean sharedSyncServletContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(props.getMaxFrameSize());
        container.setMaxTextMessageBufferSize(props.getMaxFrameSize());
        return container;
    }

    @Bean
    public WebSocketSessionRegistry webSocketSessionRegistry() {
        return new WebSocketSessionRegistry();
    }

    /**
     * 프레임 처리를 컨테이너 읽기 스레드에서 분리한다. STOMP 의 clientInboundChannel 자리다.
     */
    @Bean
    public SyncFrameExecutor syncFrameExecutor() {
        int threads = props.getDispatchThreads() > 0
                ? props.getDispatchThreads()
                : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        return new SyncFrameExecutor(threads, props.getDispatchQueueLimit());
    }

    @Bean
    public SyncTransport webSocketSyncTransport(WebSocketSessionRegistry registry) {
        return new WebSocketSyncTransport(registry);
    }

    @Bean
    public SyncSessionContext webSocketSyncSessionContext() {
        return new WebSocketSyncSessionContext();
    }

    @Bean
    public ProtoFrameDecoder protoFrameDecoder(SyncCodec codec) {
        return new ProtoFrameDecoder(requireProtoCodec(codec).getDescriptors());
    }

    @Bean
    public SyncWebSocketHandler syncWebSocketHandler(
            WebSocketSessionRegistry registry,
            ProtoFrameDecoder decoder,
            SyncCodec codec,
            SyncDispatcher dispatcher,
            PresenceSessionManager presenceSessionManager,
            PresenceRootResolver presenceRootResolver,
            SharedSyncAuthProperties authProperties,
            ObjectProvider<SyncAccessValidator> accessValidator,
            SyncFrameExecutor frameExecutor
    ) {
        return new SyncWebSocketHandler(registry, decoder, requireProtoCodec(codec), dispatcher,
                presenceSessionManager, presenceRootResolver, authProperties, frameExecutor, accessValidator);
    }

    /**
     * 앱이 SyncAccessValidator 를 직접 구현하지 않았다면 기존 StompAccessValidator 들을 재사용한다.
     * 둘 다 없으면 인가 없이 통과하므로 STOMP 경로와 동작이 같다.
     */
    @Bean
    @ConditionalOnMissingBean(SyncAccessValidator.class)
    public SyncAccessValidator stompAccessValidatorAdapter(ObjectProvider<List<StompAccessValidator>> validators) {
        return new StompAccessValidatorAdapter(validators.getIfAvailable(Collections::emptyList));
    }

    @Bean
    public WebSocketPingScheduler webSocketPingScheduler(WebSocketSessionRegistry registry) {
        return new WebSocketPingScheduler(registry, props);
    }

    private static ProtoSyncCodec requireProtoCodec(SyncCodec codec) {
        if (codec instanceof ProtoSyncCodec proto) {
            return proto;
        }
        throw new IllegalStateException(
                "sharedsync.websocket.transport=websocket 은 codec=protobuf 를 전제한다. "
                        + "현재 코덱: " + codec.getClass().getName()
                        + " — raw WS 프레임은 ClientFrame/ServerFrame 이라 JSON 코덱으로는 해석할 수 없다.");
    }
}
