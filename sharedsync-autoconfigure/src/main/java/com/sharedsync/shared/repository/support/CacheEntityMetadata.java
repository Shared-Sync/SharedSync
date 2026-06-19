package com.sharedsync.shared.repository.support;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.sharedsync.shared.annotation.Cache;
import com.sharedsync.shared.annotation.CacheEntity;
import com.sharedsync.shared.annotation.CacheId;
import com.sharedsync.shared.annotation.EntityConverter;
import com.sharedsync.shared.annotation.IgnoreShared;
import com.sharedsync.shared.annotation.ParentId;
import com.sharedsync.shared.dto.CacheDto;

/**
 * 한 캐시 엔티티({@code AutoCacheRepository} 의 제네릭 인자)로부터 도출되는 불변 메타데이터.
 *
 * <p>리플렉션으로 한 번 계산해 고정하는 값들(@CacheId/@ParentId/@EntityConverter 필드,
 * 엔티티 @Id 필드, 캐시 키 프리픽스, RedisTemplate 빈 이름, IdPool 시퀀스 설정 등)을 한 곳에 모은다.
 * 리포지토리 인스턴스(=스프링 싱글턴 빈)당 한 번 {@link #of(Class)} 로 생성된다.</p>
 *
 * @param <T>   엔티티 타입
 * @param <ID>  ID 타입
 * @param <DTO> DTO 타입
 */
public final class CacheEntityMetadata<T, ID, DTO extends CacheDto<ID>> {

    private final Class<T> entityClass;
    private final Class<DTO> dtoClass;
    private final Class<ID> idClass;
    private final String cacheKeyPrefix;
    private final String redisTemplateBeanName;
    private final Field idField;
    private final Field entityIdField;
    private final List<Field> parentIdFields;
    private final Map<Field, Class<?>> parentEntityClassMap;
    private final Method entityConverterMethod;
    private final List<Field> ignoredEntityFields;
    private final List<Field> dtoFields;
    private final String sequenceName;
    private final int allocationSize;
    private final boolean useIdPool;

    private CacheEntityMetadata(Class<T> entityClass, Class<DTO> dtoClass, Class<ID> idClass,
            String cacheKeyPrefix, String redisTemplateBeanName, Field idField, Field entityIdField,
            List<Field> parentIdFields, Map<Field, Class<?>> parentEntityClassMap, Method entityConverterMethod,
            List<Field> ignoredEntityFields, List<Field> dtoFields, String sequenceName, int allocationSize,
            boolean useIdPool) {
        this.entityClass = entityClass;
        this.dtoClass = dtoClass;
        this.idClass = idClass;
        this.cacheKeyPrefix = cacheKeyPrefix;
        this.redisTemplateBeanName = redisTemplateBeanName;
        this.idField = idField;
        this.entityIdField = entityIdField;
        this.parentIdFields = parentIdFields;
        this.parentEntityClassMap = parentEntityClassMap;
        this.entityConverterMethod = entityConverterMethod;
        this.ignoredEntityFields = ignoredEntityFields;
        this.dtoFields = dtoFields;
        this.sequenceName = sequenceName;
        this.allocationSize = allocationSize;
        this.useIdPool = useIdPool;
    }

    /**
     * 구체 리포지토리 클래스({@code XCache extends AutoCacheRepository<Entity, ID, Dto>})의
     * 제네릭 인자와 어노테이션을 분석해 메타데이터를 생성한다.
     */
    @SuppressWarnings("unchecked")
    public static <T, ID, DTO extends CacheDto<ID>> CacheEntityMetadata<T, ID, DTO> of(Class<?> repositoryClass) {
        // 제네릭 타입에서 Entity / DTO 클래스 추출
        Type superClass = repositoryClass.getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType)) {
            throw new IllegalStateException("DTO 클래스를 추출할 수 없습니다.");
        }
        Type[] typeArgs = ((ParameterizedType) superClass).getActualTypeArguments();
        Class<T> entityClass = (Class<T>) typeArgs[0]; // Entity는 첫 번째 타입 파라미터
        Class<DTO> dtoClass = (Class<DTO>) typeArgs[2];

        // @Cache 어노테이션에서 키 타입 추출
        Cache cacheAnnotation = dtoClass.getAnnotation(Cache.class);
        if (cacheAnnotation == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @CacheEntity 어노테이션이 없습니다.");
        }

        // keyType이 빈 문자열이면 클래스 이름에서 자동 생성
        String cacheKeyPrefix;
        String annotationKeyType = cacheAnnotation.keyType();
        if (annotationKeyType == null || annotationKeyType.isEmpty()) {
            // PlanDto -> "plan"
            cacheKeyPrefix = dtoClass.getSimpleName().replace("Dto", "").toLowerCase();
        } else {
            cacheKeyPrefix = annotationKeyType.toLowerCase();
        }

        // RedisTemplate 빈 이름: PlanDto -> "planRedis"
        String entityName = dtoClass.getSimpleName().replace("Dto", "");
        String redisTemplateBeanName = Character.toLowerCase(entityName.charAt(0)) + entityName.substring(1) + "Redis";

        // 필드와 메서드 찾기
        Field idField = findFieldWithAnnotation(dtoClass, CacheId.class);
        if (idField == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @CacheId 어노테이션이 붙은 필드가 없습니다.");
        }
        idField.setAccessible(true);

        List<Field> parentIdFields = new ArrayList<>();
        Map<Field, Class<?>> parentEntityClassMap = new HashMap<>();
        for (Field field : dtoClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(ParentId.class)) {
                field.setAccessible(true);
                parentIdFields.add(field);
                ParentId parentIdAnnotation = field.getAnnotation(ParentId.class);
                if (parentIdAnnotation != null && parentIdAnnotation.value() != Object.class) {
                    parentEntityClassMap.put(field, parentIdAnnotation.value());
                }
            }
        }

        Method entityConverterMethod = findMethodWithAnnotation(dtoClass, EntityConverter.class);
        if (entityConverterMethod == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @EntityConverter 어노테이션이 붙은 메서드가 없습니다.");
        }
        entityConverterMethod.setAccessible(true);

        Field entityIdField = locateEntityIdField(entityClass);
        if (entityIdField == null) {
            throw new IllegalStateException("@Id 필드를 찾을 수 없습니다: " + entityClass.getSimpleName());
        }
        entityIdField.setAccessible(true);
        Class<ID> idClass = (Class<ID>) entityIdField.getType();

        // @IgnoreShared 필드 미리 캐싱 (동기화 시 보존용)
        List<Field> ignored = new ArrayList<>();
        for (Field f : entityClass.getDeclaredFields()) {
            if (f.isAnnotationPresent(IgnoreShared.class)) {
                f.setAccessible(true);
                ignored.add(f);
            }
        }
        List<Field> ignoredEntityFields = Collections.unmodifiableList(ignored);

        List<Field> dtoFields = Arrays.stream(dtoClass.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .peek(field -> field.setAccessible(true))
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));

        // @CacheEntity가 붙은 숫자형 ID 엔티티는 무조건 IdPool 사용
        // sequenceName은 테이블명_컬럼명_seq 패턴으로 자동 유도
        String sequenceName;
        int allocationSize;
        boolean useIdPool;
        CacheEntity cacheEntityAnnotation = entityClass.getAnnotation(CacheEntity.class);
        if (cacheEntityAnnotation != null && isNumericIdType(idClass)) {
            allocationSize = cacheEntityAnnotation.allocationSize();
            sequenceName = deriveSequenceName(entityClass, entityIdField);
            useIdPool = true;
        } else {
            sequenceName = null;
            allocationSize = cacheEntityAnnotation != null ? cacheEntityAnnotation.allocationSize() : 0;
            useIdPool = false;
        }

        return new CacheEntityMetadata<>(entityClass, dtoClass, idClass, cacheKeyPrefix, redisTemplateBeanName,
                idField, entityIdField, parentIdFields, Collections.unmodifiableMap(parentEntityClassMap),
                entityConverterMethod, ignoredEntityFields, dtoFields, sequenceName, allocationSize, useIdPool);
    }

    // ===== 도출 헬퍼 (구성 시점 전용 + 연관 엔티티 분석에 재사용) =====

    /**
     * 엔티티 클래스 계층에서 {@code @jakarta.persistence.Id} 필드를 찾는다.
     * 자기 엔티티뿐 아니라 부모/연관 엔티티의 ID 타입 분석에도 재사용된다.
     */
    public static Field locateEntityIdField(Class<?> entityClass) {
        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Field findFieldWithAnnotation(Class<?> clazz,
            Class<? extends java.lang.annotation.Annotation> annotationClass) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(annotationClass)) {
                return field;
            }
        }
        return null;
    }

    private static Method findMethodWithAnnotation(Class<?> clazz,
            Class<? extends java.lang.annotation.Annotation> annotationClass) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotationClass)) {
                return method;
            }
        }
        return null;
    }

    /**
     * 엔티티 클래스와 ID 필드로부터 PostgreSQL IDENTITY 시퀀스 이름을 자동 유도한다.
     * 결과 형식: {table_name}_{column_name}_seq
     */
    private static String deriveSequenceName(Class<?> entityClass, Field idField) {
        jakarta.persistence.Table tableAnnotation = entityClass.getAnnotation(jakarta.persistence.Table.class);
        String tableName;
        if (tableAnnotation != null && tableAnnotation.name() != null && !tableAnnotation.name().isEmpty()) {
            tableName = tableAnnotation.name();
        } else {
            tableName = toSnakeCase(entityClass.getSimpleName());
        }

        jakarta.persistence.Column columnAnnotation = idField.getAnnotation(jakarta.persistence.Column.class);
        String columnName;
        if (columnAnnotation != null && columnAnnotation.name() != null && !columnAnnotation.name().isEmpty()) {
            columnName = columnAnnotation.name();
        } else {
            columnName = toSnakeCase(idField.getName());
        }

        return tableName + "_" + columnName + "_seq";
    }

    /** CamelCase 문자열을 snake_case로 변환한다. */
    private static String toSnakeCase(String s) {
        return s.replaceAll("([A-Z])", "_$1").toLowerCase().replaceFirst("^_", "");
    }

    /** ID 타입이 숫자형(Long, Integer 등)인지 확인한다. */
    private static boolean isNumericIdType(Class<?> type) {
        return Number.class.isAssignableFrom(type)
                || type == long.class || type == int.class
                || type == short.class || type == byte.class;
    }

    // ===== 접근자 =====

    public Class<T> getEntityClass() {
        return entityClass;
    }

    public Class<DTO> getDtoClass() {
        return dtoClass;
    }

    public Class<ID> getIdClass() {
        return idClass;
    }

    public String getCacheKeyPrefix() {
        return cacheKeyPrefix;
    }

    public String getRedisTemplateBeanName() {
        return redisTemplateBeanName;
    }

    public Field getIdField() {
        return idField;
    }

    public Field getEntityIdField() {
        return entityIdField;
    }

    public List<Field> getParentIdFields() {
        return parentIdFields;
    }

    public Map<Field, Class<?>> getParentEntityClassMap() {
        return parentEntityClassMap;
    }

    public Method getEntityConverterMethod() {
        return entityConverterMethod;
    }

    public List<Field> getIgnoredEntityFields() {
        return ignoredEntityFields;
    }

    public List<Field> getDtoFields() {
        return dtoFields;
    }

    public String getSequenceName() {
        return sequenceName;
    }

    public int getAllocationSize() {
        return allocationSize;
    }

    public boolean isUseIdPool() {
        return useIdPool;
    }
}
