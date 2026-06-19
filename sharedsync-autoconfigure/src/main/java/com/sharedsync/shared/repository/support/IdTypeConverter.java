package com.sharedsync.shared.repository.support;

import java.lang.reflect.Field;

/**
 * ID 값의 타입 변환과 임시/영속 판별을 담당하는 순수 협력자(스프링/Redis 비의존).
 *
 * <p>{@code AutoCacheRepository} 의 ID 관련 로직(문자열→ID 변환, DTO/엔티티 ID 타입 정규화,
 * 음수 임시 ID 판별, 엔티티 @Id 필드 읽기/쓰기)을 모은다. 모든 동작은
 * 엔티티 ID 클래스/필드와 IdPool 사용 여부에만 의존한다.</p>
 *
 * @param <T>  엔티티 타입
 * @param <ID> ID 타입
 */
public final class IdTypeConverter<T, ID> {

    private final Class<ID> idClass;
    private final Field entityIdField;
    private final boolean useIdPool;

    public IdTypeConverter(Class<ID> idClass, Field entityIdField, boolean useIdPool) {
        this.idClass = idClass;
        this.entityIdField = entityIdField;
        this.useIdPool = useIdPool;
    }

    /** 임의의 값을 이 리포지토리의 ID 타입(idClass)으로 변환한다. */
    @SuppressWarnings("unchecked")
    public ID changeType(Object id) {
        if (id == null) {
            return null;
        }
        if (idClass.isInstance(id)) {
            return (ID) id;
        }
        if (idClass.getSimpleName().equals("String")) {
            return (ID) id.toString();
        }
        if (idClass.getSimpleName().equals("Integer")) {
            return (ID) Integer.valueOf(id.toString());
        }
        if (idClass.getSimpleName().equals("Long")) {
            return (ID) Long.valueOf(id.toString());
        }
        if (idClass.getSimpleName().equals("UUID")) {
            return (ID) java.util.UUID.fromString(id.toString());
        }
        return null;
    }

    /**
     * 임의의 ID 값을 지정한 대상 타입(연관 엔티티 ID 필드 타입)으로 변환한다.
     * String, Integer/int, Long/long, Short, Byte, UUID 를 지원한다.
     */
    public Object convertIdToType(Class<?> targetType, Object idValue) {
        if (idValue == null)
            return null;
        if (targetType == null)
            return idValue;

        // already correct type
        if (targetType.isInstance(idValue))
            return idValue;

        String s = idValue.toString();
        try {
            if (targetType == String.class)
                return s;
            if (targetType == Integer.class || targetType == int.class)
                return Integer.valueOf(s);
            if (targetType == Long.class || targetType == long.class)
                return Long.valueOf(s);
            if (targetType == Short.class || targetType == short.class)
                return Short.valueOf(s);
            if (targetType == Byte.class || targetType == byte.class)
                return Byte.valueOf(s);
            if (targetType == java.util.UUID.class)
                return java.util.UUID.fromString(s);
        } catch (Exception e) {
            // fall through to return original value below
        }
        return idValue;
    }

    /** 문자열 ID를 실제 ID 타입으로 변환한다. */
    @SuppressWarnings("unchecked")
    public ID convertStringToId(String idStr) {
        if (idStr == null) {
            return null;
        }
        try {
            if (idClass.equals(String.class)) {
                return (ID) idStr;
            } else if (idClass.equals(java.util.UUID.class)) {
                return (ID) java.util.UUID.fromString(idStr);
            } else if (idClass.equals(Long.class) || idClass.equals(long.class)) {
                return (ID) Long.valueOf(idStr);
            } else if (idClass.equals(Integer.class) || idClass.equals(int.class)) {
                return (ID) Integer.valueOf(idStr);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("ID 변환 실패: " + idStr + " to " + idClass.getSimpleName());
        }
        throw new IllegalArgumentException("지원하지 않는 ID 타입입니다: " + idClass.getSimpleName());
    }

    /** 음수(레거시 임시) ID 여부. Pool 모드에서는 항상 양수이므로 임시 ID가 없다. */
    public boolean isTemporaryId(Object id) {
        if (id == null) {
            return false;
        }
        if (useIdPool) {
            return false;
        }
        if (id instanceof Number number) {
            return number.longValue() < 0L;
        }
        return false;
    }

    /** null 이 아니고 임시 ID 가 아니면 영속 ID 로 간주한다. */
    public boolean isPersistentId(Object id) {
        return id != null && !isTemporaryId(id);
    }

    /** 엔티티의 @Id 필드 값을 읽는다. */
    @SuppressWarnings("unchecked")
    public ID extractEntityId(T entity) {
        if (entity == null) {
            return null;
        }
        try {
            return (ID) entityIdField.get(entity);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("엔티티 ID 접근 실패", e);
        }
    }

    /** 엔티티의 @Id 필드 값을 설정한다. */
    public void setEntityId(T entity, Object value) {
        if (entity == null) {
            return;
        }
        try {
            entityIdField.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("엔티티 ID 설정 실패", e);
        }
    }
}
