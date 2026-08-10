package com.sharedsync.shared.autoConfig;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedsync.shared.auth.AuthenticationTokenResolver;
import com.sharedsync.shared.auth.JwtHandshakeInterceptor;
import com.sharedsync.shared.codec.SyncCodec;
import com.sharedsync.shared.history.HistoryService;
import com.sharedsync.shared.id.IdPoolService;
import com.sharedsync.shared.listener.CacheInitializer;
import com.sharedsync.shared.listener.PresenceSessionManager;
import com.sharedsync.shared.presence.core.PresenceBroadcaster;
import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.presence.core.PresenceUserRegistry;
import com.sharedsync.shared.presence.core.SharedPresenceFacade;
import com.sharedsync.shared.presence.core.UserProvider;
import com.sharedsync.shared.properties.SharedSyncAuthProperties;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;
import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.storage.InMemoryPresenceStorage;
import com.sharedsync.shared.storage.PresenceStorage;
import com.sharedsync.shared.storage.RedisPresenceStorage;
import com.sharedsync.shared.sync.CacheSyncService;
import com.sharedsync.shared.sync.PeriodicSyncScheduler;
import com.sharedsync.shared.sync.RedisSyncService;
import com.sharedsync.shared.transport.SyncSessionContext;
import com.sharedsync.shared.transport.SyncTransport;

/**
 * 프레임워크 내부 빈들의 명시적 배선.
 *
 * 예전에는 {@code @ComponentScan("com.sharedsync")} 로 긁어 왔다. 자동 설정에서의 컴포넌트 스캔은
 * 두 가지를 망가뜨린다:
 * <ul>
 *   <li>조건부 배선이 불가능해진다. STOMP 전용 빈이 raw WebSocket 모드에서도 생성되고,
 *       transport 를 바꿔도 필요 없는 것들이 그대로 뜬다.</li>
 *   <li>앱이 자기 스캔 범위에 com.sharedsync 를 포함시키면 빈이 두 벌 생긴다 —
 *       스캔은 어디서 실행되든 같은 클래스를 다시 등록한다.</li>
 * </ul>
 *
 * 생성 코드(package {@code sharedsync})만은 스캔으로 남는다. 그건 앱의 컴파일 산출물이고
 * 프레임워크가 클래스 이름을 미리 알 수 없다.
 */
@Configuration
public class SharedSyncCoreConfig {

    // ==========================================
    // 컨텍스트 / 프레즌스
    // ==========================================

    @Bean
    @ConditionalOnMissingBean
    public PresenceRootResolver presenceRootResolver() {
        return new PresenceRootResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public PresenceUserRegistry presenceUserRegistry() {
        return new PresenceUserRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public PresenceBroadcaster presenceBroadcaster(RedisSyncService redisSyncService) {
        return new PresenceBroadcaster(redisSyncService);
    }

    @Bean
    @ConditionalOnMissingBean
    public SharedPresenceFacade sharedPresenceFacade(PresenceStorage storage) {
        return new SharedPresenceFacade(storage);
    }

    @Bean
    @ConditionalOnMissingBean(PresenceStorage.class)
    @ConditionalOnProperty(name = "sharedsync.cache.type", havingValue = "memory", matchIfMissing = true)
    public PresenceStorage inMemoryPresenceStorage() {
        return new InMemoryPresenceStorage();
    }

    @Bean
    @ConditionalOnMissingBean(PresenceStorage.class)
    @ConditionalOnProperty(name = "sharedsync.cache.type", havingValue = "redis")
    public PresenceStorage redisPresenceStorage(
            @Lazy @Qualifier("presenceRedis") RedisTemplate<String, Object> presenceRedis,
            @Lazy @Qualifier("sharedSyncRedisMessageListenerContainer")
            RedisMessageListenerContainer sharedSyncRedisMessageListenerContainer) {
        return new RedisPresenceStorage(presenceRedis, sharedSyncRedisMessageListenerContainer);
    }

    @Bean
    @ConditionalOnMissingBean
    public PresenceSessionManager presenceSessionManager(
            PresenceStorage presenceStorage,
            PresenceBroadcaster presenceBroadcaster,
            UserProvider userProvider,
            CacheInitializer cacheInitializer,
            CacheSyncService cacheSyncService,
            HistoryService historyService,
            PresenceRootResolver presenceRootResolver,
            SharedSyncAuthProperties authProperties,
            SharedSyncPresenceProperties presenceProperties,
            SharedSyncWebSocketProperties webSocketProperties) {
        return new PresenceSessionManager(presenceStorage, presenceBroadcaster, userProvider, cacheInitializer,
                cacheSyncService, historyService, presenceRootResolver, authProperties, presenceProperties,
                webSocketProperties);
    }

    // ==========================================
    // 캐시 / 동기화
    // ==========================================

    @Bean
    @ConditionalOnMissingBean
    public CacheInitializer cacheInitializer(ApplicationContext context, PresenceStorage presenceStorage) {
        return new CacheInitializer(context, presenceStorage);
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheSyncService cacheSyncService(
            ObjectProvider<List<AutoCacheRepository<?, ?, ?>>> repositories,
            PresenceStorage presenceStorage) {
        return new CacheSyncService(repositories.getIfAvailable(List::of), presenceStorage);
    }

    @Bean
    @ConditionalOnMissingBean
    public PeriodicSyncScheduler periodicSyncScheduler(
            CacheSyncService cacheSyncService,
            PresenceStorage presenceStorage,
            SharedSyncPresenceProperties presenceProperties,
            IdPoolService idPoolService) {
        return new PeriodicSyncScheduler(cacheSyncService, presenceStorage, presenceProperties, idPoolService);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdPoolService idPoolService() {
        return new IdPoolService();
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisSyncService redisSyncService(
            @Lazy @Qualifier("redisSyncTemplate")
            RedisTemplate<String, com.sharedsync.shared.sync.RedisSyncMessage> redisSyncTemplate,
            SyncTransport transport,
            SyncCodec codec,
            SharedSyncWebSocketProperties props) {
        return new RedisSyncService(redisSyncTemplate, transport, codec, props);
    }

    /**
     * undo/redo 히스토리. presenceRedis 가 없으면 히스토리만 비활성화되고 편집은 그대로 동작한다.
     */
    @Bean
    @ConditionalOnMissingBean
    public HistoryService historyService(
            @Qualifier("presenceRedis") ObjectProvider<RedisTemplate<String, Object>> presenceRedis,
            ObjectProvider<List<AutoCacheRepository<?, ?, ?>>> repositories,
            RedisSyncService redisSyncService,
            ObjectMapper objectMapper,
            SyncSessionContext sessionContext) {
        return new HistoryService(presenceRedis.getIfAvailable(), repositories.getIfAvailable(List::of),
                redisSyncService, objectMapper, sessionContext);
    }

    // ==========================================
    // 인증
    // ==========================================

    /** 핸드셰이크 인증. 두 transport 모두 이 경로를 쓴다. */
    @Bean
    @ConditionalOnMissingBean
    public JwtHandshakeInterceptor jwtHandshakeInterceptor(
            AuthenticationTokenResolver tokenResolver,
            SharedSyncAuthProperties authProperties) {
        return new JwtHandshakeInterceptor(tokenResolver, authProperties);
    }
}
