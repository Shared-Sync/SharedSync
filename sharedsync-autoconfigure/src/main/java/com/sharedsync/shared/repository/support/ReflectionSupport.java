package com.sharedsync.shared.repository.support;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 여러 협력자(EntityDtoConverter, DatabaseReader/Writer)와 AutoCacheRepository 가 공유하는
 * 순수 리플렉션 유틸리티 모음. 상태가 없으므로 모두 정적 메서드다.
 */
public final class ReflectionSupport {

    private ReflectionSupport() {
    }

    /** 클래스 계층(상위 클래스 포함)의 모든 선언 필드를 반환한다. */
    public static List<Field> getAllFieldsInHierarchy(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    /** 클래스 계층에서 이름으로 필드를 찾아 접근 가능하게 만들어 반환한다(없으면 null). */
    public static Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /** 제네릭 List 타입에서 요소 타입(Class)을 추출한다(불가하면 null). */
    public static Class<?> getListElementType(Type genericType) {
        if (genericType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) genericType;
            Type[] typeArguments = parameterizedType.getActualTypeArguments();
            if (typeArguments.length > 0 && typeArguments[0] instanceof Class) {
                return (Class<?>) typeArguments[0];
            }
        }
        return null;
    }

    /**
     * 엔티티 클래스의 테이블 이름을 반환한다.
     * {@code @Table(name=...)} 이 있으면 그 이름을, 없으면 단순 클래스명을 사용한다.
     */
    public static String getTableName(Class<?> entityClass) {
        jakarta.persistence.Table table = entityClass.getAnnotation(jakarta.persistence.Table.class);
        if (table != null && !table.name().isEmpty()) {
            return table.name();
        }
        return entityClass.getSimpleName();
    }
}
