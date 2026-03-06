package com.sharedsync.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.sharedsync.shared.repository.CacheStore;
import com.sharedsync.shared.repository.InMemoryCacheStore;

/**
 * InMemory 캐시 설정 (기본값).
 * 
 * 사용법:
 * 1. 기본값: 아무것도 설정하지 않으면 인메모리 캐시 사용
 * 2. Redis 사용: application.yml에 다음 추가:
 *    sharedsync:
 *      cache:
 *        type: redis
 * 
 * 인메모리 캐시는 단일 인스턴스 환경에 적합합니다.
 * 다중 인스턴스/분산 환경에서는 Redis를 사용하세요.
 */
@Configuration
public class InMemoryCacheConfig {

    /**
     * sharedsync.cache.type이 memory이거나 설정되지 않았을 때 InMemory 캐시 사용 (기본값)
     */
    @Bean(name = "globalCacheStore")
    @Primary
    @ConditionalOnProperty(name = "sharedsync.cache.type", havingValue = "memory", matchIfMissing = true)
    @SuppressWarnings("rawtypes")
    public CacheStore inMemoryCacheStore() {
        System.out.println("[SharedSync] Using InMemory cache store");
        return new InMemoryCacheStore<>();
    }

    /**
     * Redis 연결이 없고 cache type도 지정되지 않은 경우 폴백으로 InMemory 사용
     * RedisConnectionFactory가 없을 때만 활성화
     */
    @Bean(name = "fallbackCacheStore")
    @ConditionalOnMissingBean(name = {"globalCacheStore", "redisConnectionFactory"})
    @SuppressWarnings("rawtypes")
    public CacheStore fallbackInMemoryCacheStore() {
        System.out.println("[SharedSync] Redis not available, falling back to InMemory cache store");
        return new InMemoryCacheStore<>();
    }
}
