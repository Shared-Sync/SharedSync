package com.sharedsync.shared.dto;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sharedsync.shared.annotation.CacheId;

/**
 * 공통 캐시 DTO 상위 클래스.
 * DTO 구현체가 일반 클래스로 전환되면서 최소한의 타입 계층을 제공하기 위해 도입되었습니다.
 * 현재는 표준화된 기능이 없지만, 향후 공통 동작을 추가할 수 있습니다.
 *
 * @param <ID> DTO에서 사용하는 식별자 타입
 */
public abstract class CacheDto<ID> {

    private static final Map<Class<?>, Field> ID_FIELD_CACHE = new ConcurrentHashMap<>();

    protected CacheDto() {
    }

    /**
     * DTO의 식별자를 반환합니다.
     * 구현체는 {@code @CacheId}가 지정된 필드를 통해 식별자를 노출해야 합니다.
     */
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public ID getId() {
        Field idField = ID_FIELD_CACHE.computeIfAbsent(getClass(), CacheDto::resolveCacheIdField);
        try {
            return (ID) idField.get(this);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to access @CacheId field on " + getClass().getSimpleName(), e);
        }
    }

    /**
     * DTO의 식별자를 변경합니다.
     * @return {@code this} (체이닝용)
     */
    @SuppressWarnings("unchecked")
    public <T extends CacheDto<ID>> T changeId(ID newId) {
        Field idField = ID_FIELD_CACHE.computeIfAbsent(getClass(), CacheDto::resolveCacheIdField);
        try {
            idField.set(this, newId);
            return (T) this;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to modify @CacheId field on " + getClass().getSimpleName(), e);
        }
    }

    private static Field resolveCacheIdField(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(CacheId.class))
                .findFirst()
                .map(field -> {
                    field.setAccessible(true);
                    return field;
                })
                .orElseThrow(() -> new IllegalStateException("No field annotated with @CacheId found on " + clazz.getSimpleName()));
    }
}
