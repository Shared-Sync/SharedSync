package com.sharedsync.shared.repository.support;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sharedsync.shared.dto.CacheDto;

import jakarta.persistence.EntityManager;

/**
 * EntityManager 기반 DB 영속 프리미티브 협력자.
 *
 * <p>persist/merge 저장, 다건 삭제, 연관 엔티티 식별자 추출, 그리고 IdPool 모드용
 * {@code OVERRIDING SYSTEM VALUE} 네이티브 INSERT를 제공한다. 캐시/교차-리포 조율은 포함하지 않는다
 * (그 부분은 AutoCacheRepository 파사드가 담당). 트랜잭션 경계는 호출자(@Transactional)를 따른다.</p>
 *
 * @param <T>   엔티티 타입
 * @param <ID>  ID 타입
 * @param <DTO> DTO 타입
 */
public final class DatabaseWriter<T, ID, DTO extends CacheDto<ID>> {

    private static final Logger log = LoggerFactory.getLogger(DatabaseWriter.class);

    private final CacheEntityMetadata<T, ID, DTO> metadata;
    private final IdTypeConverter<T, ID> idType;
    private final Supplier<EntityManager> entityManager;

    public DatabaseWriter(CacheEntityMetadata<T, ID, DTO> metadata, IdTypeConverter<T, ID> idType,
            Supplier<EntityManager> entityManager) {
        this.metadata = metadata;
        this.idType = idType;
        this.entityManager = entityManager;
    }

    /** EntityManager 로 엔티티 저장: ID가 없으면 persist, 있으면 merge(@IgnoreShared 보존). */
    public T saveEntity(T entity) {
        EntityManager em = entityManager.get();
        ID id = idType.extractEntityId(entity);
        if (id == null) {
            // 새 엔티티 - persist
            em.persist(entity);
            return entity;
        } else {
            // 기존 엔티티 - merge
            try {
                // @IgnoreShared 필드가 있다면 DB의 기존 값을 유지하도록 병합 전 복사
                if (!metadata.getIgnoredEntityFields().isEmpty()) {
                    T existing = em.find(metadata.getEntityClass(), id);
                    if (existing != null) {
                        for (Field f : metadata.getIgnoredEntityFields()) {
                            try {
                                Object val = f.get(existing);
                                f.set(entity, val);
                            } catch (IllegalAccessException e) {
                                // ignore
                            }
                        }
                    }
                }
                return em.merge(entity);
            } catch (jakarta.persistence.OptimisticLockException | org.hibernate.StaleObjectStateException e) {
                // 이미 다른 트랜잭션에 의해 수정/삭제된 경우 무시
                return entity;
            }
        }
    }

    /** EntityManager 로 여러 엔티티 삭제. */
    public void deleteAllEntities(List<T> entities) {
        EntityManager em = entityManager.get();
        for (T entity : entities) {
            try {
                T managed = em.contains(entity) ? entity : em.merge(entity);
                em.remove(managed);
            } catch (jakarta.persistence.OptimisticLockException | org.hibernate.StaleObjectStateException e) {
                // 이미 삭제된 경우 무시
            }
        }
    }

    /**
     * Pool ID를 가진 새 엔티티를 DB에 INSERT한다.
     * PostgreSQL의 GENERATED ALWAYS AS IDENTITY 컬럼에 명시적 ID를 넣기 위해
     * OVERRIDING SYSTEM VALUE 구문을 사용한다.
     */
    public T insertWithOverridingSystemValue(T entity) {
        EntityManager em = entityManager.get();
        Class<?> entityClass = metadata.getEntityClass();
        String tableName = ReflectionSupport.getTableName(entityClass);

        List<String> columnNames = new ArrayList<>();
        List<Object> columnValues = new ArrayList<>();

        // 엔티티의 모든 필드를 순회하며 컬럼 매핑 정보 추출
        for (Field field : ReflectionSupport.getAllFieldsInHierarchy(entityClass)) {
            field.setAccessible(true);

            // @Transient 및 static/final 필드 제외
            if (field.isAnnotationPresent(jakarta.persistence.Transient.class)
                    || Modifier.isStatic(field.getModifiers())
                    || Modifier.isFinal(field.getModifiers())) {
                continue;
            }

            // @OneToMany 등 컬렉션 관계 제외
            if (field.isAnnotationPresent(jakarta.persistence.OneToMany.class)) {
                continue;
            }

            try {
                Object value = field.get(entity);

                // @ManyToOne 관계: 연관 엔티티에서 FK ID 추출
                if (field.isAnnotationPresent(jakarta.persistence.ManyToOne.class)) {
                    jakarta.persistence.JoinColumn joinColumn = field
                            .getAnnotation(jakarta.persistence.JoinColumn.class);
                    if (joinColumn == null) {
                        // @JoinColumn이 없으면 건너뜀 (JPA 기본 매핑 사용 시)
                        continue;
                    }

                    boolean isNullable = joinColumn.nullable();

                    if (value == null) {
                        if (!isNullable) {
                            log.warn("[AutoCacheRepository] Required FK is null for {}.{}, skipping INSERT",
                                    entityClass.getSimpleName(), field.getName());
                            return entity;
                        }
                        // nullable FK가 null이면 컬럼 포함하지 않음
                        continue;
                    }

                    // 연관 엔티티에서 FK 값 추출 (Hibernate 프록시 지원)
                    Object fkValue = extractIdFromRelatedEntity(value);
                    if (fkValue == null) {
                        if (!isNullable) {
                            log.warn(
                                    "[AutoCacheRepository] FK value is null for {}.{} (entity exists but ID is null), skipping INSERT",
                                    entityClass.getSimpleName(), field.getName());
                            return entity;
                        }
                        continue;
                    }

                    columnNames.add(joinColumn.name());
                    columnValues.add(fkValue);
                    continue;
                }

                // 일반 컬럼
                String columnName = getColumnName(field);
                if (columnName != null && value != null) {
                    columnNames.add(columnName);
                    columnValues.add(value);
                }
            } catch (IllegalAccessException e) {
                log.warn("[AutoCacheRepository] Failed to access field {}: {}",
                        field.getName(), e.getMessage());
            }
        }

        if (columnNames.isEmpty()) {
            log.error("[AutoCacheRepository] No columns to insert for entity: {}", entityClass.getSimpleName());
            return entity;
        }

        // INSERT SQL 생성: OVERRIDING SYSTEM VALUE
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(tableName).append(" (");
        sql.append(String.join(", ", columnNames));
        sql.append(") OVERRIDING SYSTEM VALUE VALUES (");
        sql.append(columnNames.stream().map(c -> "?").collect(Collectors.joining(", ")));
        sql.append(")");

        try {
            jakarta.persistence.Query query = em.createNativeQuery(sql.toString());
            for (int i = 0; i < columnValues.size(); i++) {
                query.setParameter(i + 1, columnValues.get(i));
            }
            query.executeUpdate();
            log.info("[AutoCacheRepository] Inserted entity with Pool ID: table={}, id={}",
                    tableName, idType.extractEntityId(entity));
        } catch (Exception e) {
            log.error("[AutoCacheRepository] Native INSERT failed for {}: {}",
                    entityClass.getSimpleName(), e.getMessage());
            // Native INSERT 실패 시 fallback: 기존 persist 방식 시도
            // 트랜잭션이 깨진 상태이므로, 현재 엔티티를 detach 후 새 persist 시도는 불가능.
            // DB에 저장되지 않았지만 캐시에는 유지되므로, 다음 주기적 동기화에서 재시도됩니다.
            log.warn("[AutoCacheRepository] Entity will be retried on next periodic sync. id={}",
                    idType.extractEntityId(entity));
        }

        return entity;
    }

    /**
     * 연관 엔티티에서 @Id 필드 값을 추출한다(Hibernate 프록시 지원).
     */
    public Object extractIdFromRelatedEntity(Object relatedEntity) {
        if (relatedEntity == null)
            return null;

        // Hibernate 프록시인 경우 실제 클래스 가져오기
        Class<?> clazz = relatedEntity.getClass();
        try {
            if (relatedEntity instanceof org.hibernate.proxy.HibernateProxy proxy) {
                Object identifier = proxy.getHibernateLazyInitializer().getIdentifier();
                if (identifier != null) {
                    return identifier;
                }
                clazz = proxy.getHibernateLazyInitializer().getPersistentClass();
            }
        } catch (Exception e) {
            // Hibernate 프록시가 아닌 경우 무시
        }

        // @Id 필드 탐색 (상속 계층 포함)
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)
                        || field.isAnnotationPresent(jakarta.persistence.EmbeddedId.class)) {
                    field.setAccessible(true);
                    try {
                        return field.get(relatedEntity);
                    } catch (IllegalAccessException e) {
                        // Getter 메서드로 시도
                        try {
                            String getterName = "get" + Character.toUpperCase(field.getName().charAt(0))
                                    + field.getName().substring(1);
                            java.lang.reflect.Method getter = clazz.getMethod(getterName);
                            return getter.invoke(relatedEntity);
                        } catch (Exception ex) {
                            log.warn("[AutoCacheRepository] Cannot access @Id field '{}' in {}: {}",
                                    field.getName(), clazz.getSimpleName(), e.getMessage());
                        }
                    }
                }
            }
            current = current.getSuperclass();
        }

        log.warn("[AutoCacheRepository] No @Id field found in {}", clazz.getSimpleName());
        return null;
    }

    /**
     * 필드의 DB 컬럼 이름을 반환한다. @Column 이 있으면 그 이름을, 없으면 camelCase→snake_case 변환.
     */
    private String getColumnName(Field field) {
        jakarta.persistence.Column column = field.getAnnotation(jakarta.persistence.Column.class);
        if (column != null && !column.name().isEmpty()) {
            return column.name();
        }
        if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
            jakarta.persistence.Column idColumn = field.getAnnotation(jakarta.persistence.Column.class);
            if (idColumn != null && !idColumn.name().isEmpty()) {
                return idColumn.name();
            }
        }
        return camelToSnake(field.getName());
    }

    /** camelCase 를 snake_case 로 변환한다. */
    private String camelToSnake(String camelCase) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
