package com.sharedsync.shared.repository;

import java.util.List;
import java.util.Set;

import org.springframework.data.redis.core.RedisTemplate;

/**
 * Redis 기반 CacheStore 구현체
 * 기존 RedisTemplate을 래핑하여 CacheStore 인터페이스를 구현합니다.
 *
 * @param <V> 값 타입 (DTO)
 */
public class RedisCacheStore<V> implements CacheStore<V> {

    private final RedisTemplate<String, V> redisTemplate;

    public RedisCacheStore(RedisTemplate<String, V> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public V get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public void set(String key, V value) {
        redisTemplate.opsForValue().set(key, value);
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public List<V> multiGet(List<String> keys) {
        return redisTemplate.opsForValue().multiGet(keys);
    }

    @Override
    public Set<String> keys(String pattern) {
        return redisTemplate.keys(pattern);
    }

    @Override
    public Long decrement(String key) {
        return redisTemplate.opsForValue().decrement(key);
    }

    @Override
    public void addToSet(String key, String value) {
        redisTemplate.execute(new org.springframework.data.redis.core.RedisCallback<Object>() {
            @Override
            public Object doInRedis(org.springframework.data.redis.connection.RedisConnection connection) throws org.springframework.dao.DataAccessException {
                connection.sAdd(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), 
                               value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return null;
            }
        });
    }

    @Override
    public void removeFromSet(String key, String value) {
        redisTemplate.execute(new org.springframework.data.redis.core.RedisCallback<Object>() {
            @Override
            public Object doInRedis(org.springframework.data.redis.connection.RedisConnection connection) throws org.springframework.dao.DataAccessException {
                connection.sRem(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), 
                               value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return null;
            }
        });
    }

    @Override
    public Set<String> getSet(String key) {
        return redisTemplate.execute(new org.springframework.data.redis.core.RedisCallback<Set<String>>() {
            @Override
            public Set<String> doInRedis(org.springframework.data.redis.connection.RedisConnection connection) throws org.springframework.dao.DataAccessException {
                Set<byte[]> members = connection.sMembers(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (members == null) return java.util.Collections.emptySet();
                return members.stream()
                        .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                        .collect(java.util.stream.Collectors.toSet());
            }
        });
    }

    @Override
    public void hashSet(String key, String field, V value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    @Override
    public V hashGet(String key, String field) {
        return (V) redisTemplate.opsForHash().get(key, field);
    }

    @Override
    public List<V> hashMutiGet(String key, List<String> fields) {
        return (List<V>) (List<?>) redisTemplate.opsForHash().multiGet(key, (List<Object>) (List<?>) fields);
    }

    @Override
    public void hashDelete(String key, String field) {
        redisTemplate.opsForHash().delete(key, field);
    }

    @Override
    public Set<String> hashkeys(String key) {
        Set<Object> keys = redisTemplate.opsForHash().keys(key);
        if (keys == null) return java.util.Collections.emptySet();
        return keys.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public void hashSetString(String key, String field, String value) {
        redisTemplate.execute(new org.springframework.data.redis.core.RedisCallback<Object>() {
            @Override
            public Object doInRedis(org.springframework.data.redis.connection.RedisConnection connection) throws org.springframework.dao.DataAccessException {
                connection.hSet(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), 
                               field.getBytes(java.nio.charset.StandardCharsets.UTF_8), 
                               value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return null;
            }
        });
    }

    @Override
    public String hashGetString(String key, String field) {
        return redisTemplate.execute(new org.springframework.data.redis.core.RedisCallback<String>() {
            @Override
            public String doInRedis(org.springframework.data.redis.connection.RedisConnection connection) throws org.springframework.dao.DataAccessException {
                byte[] value = connection.hGet(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), 
                                             field.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return value != null ? new String(value, java.nio.charset.StandardCharsets.UTF_8) : null;
            }
        });
    }

    /**
     * 내부 RedisTemplate 접근 (하위 호환용)
     */
    public RedisTemplate<String, V> getRedisTemplate() {
        return redisTemplate;
    }
}
