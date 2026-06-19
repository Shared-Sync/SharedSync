package com.sharedsync.shared.repository.support;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import com.sharedsync.shared.annotation.ParentId;
import com.sharedsync.shared.annotation.TableName;
import com.sharedsync.shared.dto.CacheDto;

import jakarta.persistence.EntityManager;

/**
 * DTO ↔ Entity 변환 및 병합을 담당하는 협력자.
 *
 * <p>{@code @EntityConverter} 메서드 호출(필요 시 연관 엔티티 참조 주입), {@code fromEntity} 역변환,
 * 엔티티/DTO 필드 병합을 담당한다. 메타데이터/ID변환기/리플렉션 유틸에 의존하며,
 * {@link EntityManager} 는 지연 주입을 위해 {@link Supplier} 로 받는다(스프링 필드주입 시점 이후 평가).</p>
 *
 * @param <T>   엔티티 타입
 * @param <ID>  ID 타입
 * @param <DTO> DTO 타입
 */
public final class EntityDtoConverter<T, ID, DTO extends CacheDto<ID>> {

    private final CacheEntityMetadata<T, ID, DTO> metadata;
    private final IdTypeConverter<T, ID> idType;
    private final Supplier<EntityManager> entityManager;

    public EntityDtoConverter(CacheEntityMetadata<T, ID, DTO> metadata, IdTypeConverter<T, ID> idType,
            Supplier<EntityManager> entityManager) {
        this.metadata = metadata;
        this.idType = idType;
        this.entityManager = entityManager;
    }

    @SuppressWarnings("unchecked")
    public T convertToEntity(DTO dto) {
        try {
            // 필요한 연관 엔티티 참조를 자동 주입해서 Entity 변환
            Object[] parameters = buildEntityConverterParameters(dto);
            return (T) metadata.getEntityConverterMethod().invoke(dto, parameters);
        } catch (Exception e) {
            throw new RuntimeException("Entity 변환에 실패했습니다: " + dto, e);
        }
    }

    @SuppressWarnings("unchecked")
    public DTO convertToDto(T entity) {
        if (entity == null) {
            return null;
        }

        try {
            Method fromEntityMethod = metadata.getDtoClass().getMethod("fromEntity", metadata.getEntityClass());
            return (DTO) fromEntityMethod.invoke(null, entity);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(metadata.getDtoClass().getSimpleName() + "에 fromEntity 메서드가 필요합니다.", e);
        } catch (Exception e) {
            throw new RuntimeException("Entity를 DTO로 변환하는 데 실패했습니다.", e);
        }
    }

    /**
     * Entity의 필드를 다른 Entity의 null이 아닌 값으로 업데이트(범용 리플렉션). ID는 건너뛴다.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void mergeEntityFields(T target, T source) {
        if (target == null || source == null) {
            throw new IllegalArgumentException("target과 source는 null일 수 없습니다.");
        }

        try {
            Class<?> entityClass = target.getClass();

            for (Field field : entityClass.getDeclaredFields()) {
                field.setAccessible(true);

                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    continue;
                }

                if (field.isAnnotationPresent(jakarta.persistence.ManyToOne.class) ||
                        field.isAnnotationPresent(jakarta.persistence.OneToMany.class) ||
                        field.isAnnotationPresent(jakarta.persistence.OneToOne.class) ||
                        field.isAnnotationPresent(jakarta.persistence.ManyToMany.class)) {

                    Object sourceValue = field.get(source);
                    if (sourceValue != null) {
                        if (sourceValue instanceof Collection<?> sourceCollection) {
                            Object targetValue = field.get(target);
                            if (targetValue instanceof Collection targetCollection
                                    && targetCollection != sourceCollection) {
                                try {
                                    targetCollection.clear();
                                    ((Collection) targetCollection).addAll(sourceCollection);
                                } catch (Exception e) {
                                    field.set(target, sourceValue);
                                }
                                continue;
                            }
                        }
                        field.set(target, sourceValue);
                    }
                    continue;
                }

                Object sourceValue = field.get(source);
                if (sourceValue != null) {
                    field.set(target, sourceValue);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Entity 필드 병합 실패", e);
        }
    }

    /**
     * 기존 DTO를 새 DTO의 null이 아닌 값으로 병합한다(ID 제외).
     */
    public DTO mergeDto(DTO existingDto, DTO newDto) {
        try {
            Field idField = metadata.getIdField();
            for (Field field : metadata.getDtoFields()) {
                if (field.equals(idField)) {
                    continue;
                }
                Object newValue = field.get(newDto);
                if (newValue != null) {
                    field.set(existingDto, newValue);
                }
            }
            return existingDto;
        } catch (Exception e) {
            throw new RuntimeException("DTO 병합 실패: " + newDto, e);
        }
    }

    /** DTO의 @CacheId 필드 값을 설정한다. */
    public DTO updateDtoWithId(DTO dto, ID newId) {
        try {
            metadata.getIdField().set(dto, newId);
            return dto;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("DTO ID 업데이트 실패: " + dto, e);
        }
    }

    // ===== 내부 변환 보조 =====

    @SuppressWarnings("unchecked")
    private Object[] buildEntityConverterParameters(DTO dto) throws Exception {
        Method converter = metadata.getEntityConverterMethod();
        Class<?>[] parameterTypes = converter.getParameterTypes();
        Object[] params = new Object[parameterTypes.length];
        Type[] genericParameterTypes = converter.getGenericParameterTypes();
        EntityManager em = entityManager.get();

        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> paramType = parameterTypes[i];
            Type genericType = genericParameterTypes[i];

            Class<?> expectedEntityClass;
            if (List.class.isAssignableFrom(paramType)) {
                expectedEntityClass = ReflectionSupport.getListElementType(genericType);
            } else {
                expectedEntityClass = paramType;
            }

            if (List.class.isAssignableFrom(paramType)) {
                Class<?> elementType = expectedEntityClass;
                if (elementType != null) {
                    List<?> idList = extractRelatedIdList(dto, elementType);
                    if (idList != null && !idList.isEmpty()) {
                        List<Object> entities = new ArrayList<>();
                        for (Object id : idList) {
                            try {
                                Object normalizedId = idType.changeType(id);
                                Object ref = em.getReference(elementType, normalizedId);
                                entities.add(ref);
                            } catch (Exception e) {
                                // skip missing/invalid ids
                            }
                        }
                        params[i] = entities;
                    } else {
                        params[i] = new ArrayList<>();
                    }
                } else {
                    params[i] = new ArrayList<>();
                }
            } else {
                Object relatedId = extractRelatedId(dto, i);
                if (relatedId == null) {
                    params[i] = null;
                } else {
                    try {
                        if (expectedEntityClass != null) {
                            try {
                                Field relatedIdField = CacheEntityMetadata.locateEntityIdField(expectedEntityClass);
                                Class<?> relatedIdType = relatedIdField != null ? relatedIdField.getType() : null;
                                Object normalized = idType.convertIdToType(relatedIdType, relatedId);
                                Object ref = em.getReference(expectedEntityClass, normalized);
                                params[i] = ref;
                            } catch (Exception e) {
                                params[i] = null;
                            }
                        } else {
                            params[i] = null;
                        }
                    } catch (Exception e) {
                        params[i] = null;
                    }
                }
            }
        }

        return params;
    }

    private List<?> extractRelatedIdList(DTO dto, Class<?> elementType) {
        String tableName = ReflectionSupport.getTableName(elementType);
        for (Field field : ReflectionSupport.getAllFieldsInHierarchy(metadata.getDtoClass())) {
            TableName tableNameAnnotation = field.getAnnotation(TableName.class);
            if (tableNameAnnotation != null && tableNameAnnotation.value().equalsIgnoreCase(tableName)) {
                field.setAccessible(true);
                try {
                    Object value = field.get(dto);
                    if (value instanceof List) {
                        return (List<?>) value;
                    }
                } catch (IllegalAccessException e) {
                    // 무시
                }
            }
        }
        return null;
    }

    private Object extractRelatedId(DTO dto, int parameterIndex) {
        try {
            Method converter = metadata.getEntityConverterMethod();
            Class<?>[] paramTypes = converter.getParameterTypes();
            Type[] genericParamTypes = converter.getGenericParameterTypes();
            Class<?> entityClass = null;
            if (parameterIndex < paramTypes.length) {
                Class<?> paramType = paramTypes[parameterIndex];
                if (List.class.isAssignableFrom(paramType)) {
                    entityClass = ReflectionSupport.getListElementType(genericParamTypes[parameterIndex]);
                } else {
                    entityClass = paramType;
                }
            }

            if (entityClass == null) {
                return null;
            }

            // 0순위: @TableName 어노테이션 매칭 (테이블 이름 기반)
            String tableName = ReflectionSupport.getTableName(entityClass);
            for (Field field : ReflectionSupport.getAllFieldsInHierarchy(metadata.getDtoClass())) {
                TableName tableNameAnnotation = field.getAnnotation(TableName.class);
                if (tableNameAnnotation != null && tableNameAnnotation.value().equalsIgnoreCase(tableName)) {
                    field.setAccessible(true);
                    Object val = field.get(dto);
                    if (val != null)
                        return val;
                }
            }

            // 1순위: DTO에서 @ParentId(entityClass)가 붙은 필드 찾기
            for (Field field : ReflectionSupport.getAllFieldsInHierarchy(metadata.getDtoClass())) {
                field.setAccessible(true);
                ParentId parentIdAnnotation = field.getAnnotation(ParentId.class);
                if (parentIdAnnotation != null && parentIdAnnotation.value() == entityClass) {
                    Object idValue = field.get(dto);
                    if (idValue != null) {
                        return idValue;
                    }
                }
            }

            return null;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("관련 ID 추출 실패: parameterIndex=" + parameterIndex, e);
        }
    }
}
