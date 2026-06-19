package com.sharedsync.shared.repository.support;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.sharedsync.shared.dto.CacheDto;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/**
 * JPA Criteria 기반 DB 엔티티 조회 협력자(읽기 전용, 캐시 비의존).
 *
 * <p>부모 ID 기준 조회, 루트 전체 조회, ID 단건 조회(연관 LEFT JOIN FETCH 포함)를 제공한다.
 * Repository에 별도 메서드가 없어도 메타데이터/리플렉션만으로 동작한다.
 * {@link EntityManager} 는 {@link Supplier} 로 지연 주입받는다.</p>
 *
 * @param <T>   엔티티 타입
 * @param <ID>  ID 타입
 * @param <DTO> DTO 타입
 */
public final class DatabaseReader<T, ID, DTO extends CacheDto<ID>> {

    private final CacheEntityMetadata<T, ID, DTO> metadata;
    private final IdTypeConverter<T, ID> idType;
    private final Supplier<EntityManager> entityManager;

    public DatabaseReader(CacheEntityMetadata<T, ID, DTO> metadata, IdTypeConverter<T, ID> idType,
            Supplier<EntityManager> entityManager) {
        this.metadata = metadata;
        this.idType = idType;
        this.entityManager = entityManager;
    }

    public List<T> loadEntitiesByParentId(Object parentId) {
        return loadEntitiesByParentId(parentId, null);
    }

    public List<T> loadEntitiesByParentId(Object parentId, Class<?> parentClass) {
        if (entityManager.get() == null) {
            return Collections.emptyList();
        }

        // 부모 필드가 있으면 Criteria로 조회
        if (!metadata.getParentIdFields().isEmpty()) {
            try {
                return loadEntitiesByCriteria(parentId, parentClass);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            // 부모 필드가 없으면 (루트 엔티티) 전체 조회
            try {
                return loadAllEntitiesByCriteria();
            } catch (Exception e) {
                System.err.println("[SharedSync] Criteria API findAll 실패: " + e.getMessage());
            }
        }

        return Collections.emptyList();
    }

    /**
     * JPA Criteria API를 사용하여 parentId로 엔티티 조회 (Repository 메서드 불필요).
     */
    @SuppressWarnings("unchecked")
    private List<T> loadEntitiesByCriteria(Object parentId, Class<?> targetParentClass) {
        Class<T> entityClass = metadata.getEntityClass();
        EntityManager em = entityManager.get();

        if (metadata.getParentEntityClassMap().isEmpty() || em == null) {
            return loadAllEntitiesByCriteria();
        }

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> query = (CriteriaQuery<T>) cb.createQuery(entityClass);
        Root<T> root = (Root<T>) query.from(entityClass);

        List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
        for (Class<?> parentClass : metadata.getParentEntityClassMap().values()) {
            // 특정 부모 클래스가 지정된 경우 해당 클래스만 처리
            if (targetParentClass != null && !parentClass.equals(targetParentClass)) {
                continue;
            }

            // Entity 클래스 계층에서 해당 부모 타입을 가진 필드 찾기
            for (Field field : ReflectionSupport.getAllFieldsInHierarchy(entityClass)) {
                if (field.getType().isAssignableFrom(parentClass)) {
                    try {
                        // 부모 엔티티의 @Id 필드 정보를 동적으로 가져옴
                        Field pIdField = CacheEntityMetadata.locateEntityIdField(parentClass);
                        if (pIdField == null)
                            continue;

                        String idFieldName = pIdField.getName();
                        Class<?> pIdType = pIdField.getType();

                        // parentId(보통 String)를 부모 ID의 실제 타입(UUID, Integer 등)으로 변환
                        Object normalizedParentId = idType.convertIdToType(pIdType, parentId);

                        jakarta.persistence.criteria.Path<?> parentPath = root.get(field.getName());
                        jakarta.persistence.criteria.Path<?> parentIdPath = parentPath.get(idFieldName);
                        predicates.add(cb.equal(parentIdPath, normalizedParentId));
                    } catch (Exception e) {
                        // JPA 필드가 아니거나 id 필드가 없는 경우 무시하고 로그 출력
                        System.err.println("[SharedSync][WARN] Failed to build predicate for field " + field.getName()
                                + ": " + e.getMessage());
                    }
                }
            }
        }

        if (predicates.isEmpty()) {
            // 부모 정보가 있는 엔티티임에도 조건을 찾지 못한 경우, 전체 조회를 하지 않고 빈 목록 반환 (보안 및 격리)
            return Collections.emptyList();
        }

        if (predicates.size() == 1) {
            query.where(predicates.get(0));
        } else {
            query.where(cb.or(predicates.toArray(new jakarta.persistence.criteria.Predicate[0])));
        }

        return em.createQuery(query).getResultList();
    }

    /**
     * JPA Criteria API를 사용하여 모든 엔티티 조회 (루트 엔티티용).
     */
    @SuppressWarnings("unchecked")
    private List<T> loadAllEntitiesByCriteria() {
        Class<T> entityClass = metadata.getEntityClass();
        EntityManager em = entityManager.get();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> query = (CriteriaQuery<T>) cb.createQuery(entityClass);
        query.from(entityClass);

        return em.createQuery(query).getResultList();
    }

    /**
     * JPA Criteria API를 사용하여 ID로 단일 엔티티 조회 (연관 관계 LEFT JOIN FETCH).
     */
    @SuppressWarnings("unchecked")
    public T loadEntityByIdCriteria(ID id) {
        Class<T> entityClass = metadata.getEntityClass();
        EntityManager em = entityManager.get();

        if (em == null) {
            return null;
        }

        try {
            List<String> relationsToFetch = new ArrayList<>();

            for (Field dtoField : metadata.getDtoFields()) {
                String dtoFieldName = dtoField.getName();
                if (dtoFieldName == null)
                    continue;
                if (dtoFieldName.endsWith("Id")) {
                    String entitySimple = dtoFieldName.substring(0, dtoFieldName.length() - 2); // e.g. "User"
                    if (entitySimple.isEmpty())
                        continue;
                    String candidate = Character.toLowerCase(entitySimple.charAt(0)) + entitySimple.substring(1);

                    for (Field f : ReflectionSupport.getAllFieldsInHierarchy(entityClass)) {
                        String relationName = null;

                        // 1) 필드명 또는 필드 타입으로 매칭
                        if (f.getName().equals(candidate) || f.getType().getSimpleName().equals(entitySimple)) {
                            relationName = f.getName();
                        }

                        // 2) @JoinColumn(name = "...")가 있으면 컬럼명으로 매칭
                        try {
                            jakarta.persistence.JoinColumn jc = f.getAnnotation(jakarta.persistence.JoinColumn.class);
                            if (jc != null) {
                                String jcName = jc.name();
                                if (jcName != null && !jcName.isBlank()) {
                                    if (jcName.toLowerCase().contains(candidate.toLowerCase())) {
                                        relationName = f.getName();
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                            // ignore reflection issues
                        }

                        if (relationName != null) {
                            if (!relationsToFetch.contains(relationName))
                                relationsToFetch.add(relationName);
                            break;
                        }
                    }
                }
            }

            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<T> query = (CriteriaQuery<T>) cb.createQuery(entityClass);
            Root<T> root = (Root<T>) query.from(entityClass);

            // add fetch joins
            Set<String> uniq = new LinkedHashSet<>(relationsToFetch);
            for (String rel : uniq) {
                try {
                    root.fetch(rel, jakarta.persistence.criteria.JoinType.LEFT);
                } catch (IllegalArgumentException ignored) {
                    // ignore invalid relation names
                }
            }

            query.select(root).where(cb.equal(root.get(metadata.getEntityIdField().getName()), id));

            try {
                return em.createQuery(query).getSingleResult();
            } catch (jakarta.persistence.NoResultException nre) {
                return null;
            }
        } catch (Exception e) {
            // Fallback to simple find() if anything goes wrong
            try {
                return em.find(entityClass, id);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
