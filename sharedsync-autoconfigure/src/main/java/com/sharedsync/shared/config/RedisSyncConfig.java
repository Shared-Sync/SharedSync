package com.sharedsync.shared.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;
import com.sharedsync.shared.sync.RedisSyncMessage;
import com.sharedsync.shared.sync.RedisSyncService;

import lombok.RequiredArgsConstructor;

@lombok.extern.slf4j.Slf4j
@Configuration
@ConditionalOnProperty(name = "sharedsync.websocket.redis-sync.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedisSyncConfig {

    private final SharedSyncWebSocketProperties props;

    @Bean
    public org.springframework.beans.factory.InitializingBean redisSyncMessageListenerRegistar(
            @Qualifier("sharedSyncRedisMessageListenerContainer") RedisMessageListenerContainer container,
            MessageListenerAdapter listenerAdapter) {
        return () -> container.addMessageListener(listenerAdapter, new ChannelTopic(props.getRedisSync().getChannel()));
    }

    @Bean
    public RedisTemplate<String, RedisSyncMessage> redisSyncTemplate(
            @Qualifier("pubSubConnectionFactory") ObjectProvider<RedisConnectionFactory> pubSubFactoryProvider,
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, RedisSyncMessage> template = new RedisTemplate<>();
        template.setConnectionFactory(pubSubFactoryProvider.getIfAvailable(() -> connectionFactory));
        template.setKeySerializer(new StringRedisSerializer());

        // Use a clean ObjectMapper (without DefaultTyping) to avoid metadata in JSON
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Jackson2JsonRedisSerializer<RedisSyncMessage> serializer = new Jackson2JsonRedisSerializer<>(mapper,
                RedisSyncMessage.class);
        template.setValueSerializer(serializer);
        return template;
    }

    @Bean
    public MessageListenerAdapter listenerAdapter(
            @org.springframework.context.annotation.Lazy RedisSyncService redisSyncService) {
        ObjectMapper cleanMapper = new ObjectMapper();
        cleanMapper.registerModule(new JavaTimeModule());
        cleanMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        MessageListenerAdapter adapter = new MessageListenerAdapter(new Object() {
            @SuppressWarnings("unused")
            public void handleMessage(Object message) {
                try {
                    RedisSyncMessage syncMessage;
                    if (message instanceof RedisSyncMessage) {
                        syncMessage = (RedisSyncMessage) message;
                    } else if (message instanceof byte[]) {
                        syncMessage = cleanMapper.readValue((byte[]) message, RedisSyncMessage.class);
                    } else {
                        syncMessage = cleanMapper.convertValue(message, RedisSyncMessage.class);
                    }
                    redisSyncService.handleMessage(syncMessage);
                } catch (Exception e) {
                    // 여기서 삼키면 다중 인스턴스 환경에서 "다른 사람 화면에만 안 뜬다"가
                    // 아무 신호 없이 발생한다. 이 인스턴스에 붙은 세션들은 그 편집을 영영 못 받는다.
                    log.error("[SharedSync] Redis 동기화 메시지 처리 실패 (이 인스턴스의 세션들은 "
                            + "해당 편집을 받지 못한다) type={}: {}",
                            message == null ? "null" : message.getClass().getName(), e.getMessage(), e);
                }
            }
        }, "handleMessage");

        Jackson2JsonRedisSerializer<RedisSyncMessage> serializer = new Jackson2JsonRedisSerializer<>(cleanMapper,
                RedisSyncMessage.class);
        adapter.setSerializer(serializer);
        return adapter;
    }
}
