package com.sharedsync.shared.repository;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;

import com.sharedsync.shared.annotation.Cache;
import com.sharedsync.shared.annotation.CacheEntity;
import com.sharedsync.shared.annotation.CacheId;
import com.sharedsync.shared.annotation.EntityConverter;
import com.sharedsync.shared.annotation.IgnoreShared;
import com.sharedsync.shared.annotation.ParentId;
import com.sharedsync.shared.annotation.TableName;
import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.history.HistoryAction;
import com.sharedsync.shared.id.IdPoolService;
import com.sharedsync.shared.storage.PresenceStorage;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import lombok.extern.slf4j.Slf4j;

/**
 * 완전 자동화된 캐시 리포지토리
 * DTO에 어노테이션만 추가하면 모든 CRUD 및 DB 동기화 기능이 자동으로 구현됩니다.
 *
 * @param <T>   엔티티 타입
 * @param <ID>  ID 타입
 * @param <DTO> DTO 타입
 */
@Slf4j
public abstract class AutoCacheRepository<T, ID, DTO extends CacheDto<ID>> implements CacheRepository<T, ID, DTO> {

    @Autowired
    private ApplicationContext applicationContext;

    @PersistenceContext
    private EntityManager entityManager;

    private final Class<DTO> dtoClass;
    private final String cacheKeyPrefix;
    private final Field idField;
    private final List<Field> parentIdFields;
    private final Map<Field, Class<?>> parentEntityClassMap;
    private final Method entityConverterMethod;
    private final Field entityIdField;
    private final Class<ID> idClass;
    private final String redisTemplateBeanName;
    private final List<Field> ignoredEntityFields;

    private final List<Field> dtoFields;

    // ID Pool 관련 필드
    private final String sequenceName;
    private final int allocationSize;
    private final boolean useIdPool;

    public Class<DTO> getDtoClass() {
        return dtoClass;
    }

    @SuppressWarnings("unchecked")
    public AutoCacheRepository() {
        // 제네릭 타입에서 DTO 클래스 추출
        Type superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType) {
            Type[] typeArgs = ((ParameterizedType) superClass).getActualTypeArguments();
            this.dtoClass = (Class<DTO>) typeArgs[2];
        } else {
            throw new IllegalStateException("DTO 클래스를 추출할 수 없습니다.");
        }

        // @CacheEntity 어노테이션에서 키 타입 추출
        Cache cacheAnnotation = dtoClass.getAnnotation(Cache.class);
        if (cacheAnnotation == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @CacheEntity 어노테이션이 없습니다.");
        }

        // keyType이 빈 문자열이면 클래스 이름에서 자동 생성
        String annotationKeyType = cacheAnnotation.keyType();
        if (annotationKeyType == null || annotationKeyType.isEmpty()) {
            // PlanDto -> "plan"
            this.cacheKeyPrefix = dtoClass.getSimpleName().replace("Dto", "").toLowerCase();
        } else {
            this.cacheKeyPrefix = annotationKeyType.toLowerCase();
        }

        // @AutoRedisTemplate 어노테이션에서 Redis 템플릿 이름 추출
        String entityName = dtoClass.getSimpleName().replace("Dto", "");
        this.redisTemplateBeanName = Character.toLowerCase(entityName.charAt(0)) + entityName.substring(1) + "Redis";

        // 필드와 메서드 찾기
        this.idField = findFieldWithAnnotation(dtoClass, CacheId.class);
        if (this.idField == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @CacheId 어노테이션이 붙은 필드가 없습니다.");
        }
        this.idField.setAccessible(true);

        this.parentIdFields = new ArrayList<>();
        this.parentEntityClassMap = new java.util.HashMap<>();

        for (Field field : dtoClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(ParentId.class)) {
                field.setAccessible(true);
                this.parentIdFields.add(field);
                ParentId parentIdAnnotation = field.getAnnotation(ParentId.class);
                if (parentIdAnnotation != null && parentIdAnnotation.value() != Object.class) {
                    this.parentEntityClassMap.put(field, parentIdAnnotation.value());
                }
            }
        }

        this.entityConverterMethod = findMethodWithAnnotation(dtoClass, EntityConverter.class);
        if (this.entityConverterMethod == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @EntityConverter 어노테이션이 붙은 메서드가 없습니다.");
        }
        this.entityConverterMethod.setAccessible(true);

        Field detectedEntityIdField = locateEntityIdField(getEntityClass());
        if (detectedEntityIdField == null) {
            throw new IllegalStateException("@Id 필드를 찾을 수 없습니다: " + getEntityClass().getSimpleName());
        }
        detectedEntityIdField.setAccessible(true);
        this.entityIdField = detectedEntityIdField;
        @SuppressWarnings("unchecked")
        Class<ID> detectedIdClass = (Class<ID>) detectedEntityIdField.getType();
        this.idClass = detectedIdClass;

        // @IgnoreShared 필드 미리 캐싱 (동기화 시 보존용)
        List<Field> ignored = new ArrayList<>();
        for (Field f : getEntityClass().getDeclaredFields()) {
            if (f.isAnnotationPresent(IgnoreShared.class)) {
                f.setAccessible(true);
                ignored.add(f);
            }
        }
        this.ignoredEntityFields = Collections.unmodifiableList(ignored);

        this.dtoFields = Arrays.stream(dtoClass.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .peek(field -> field.setAccessible(true))
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));

        // @CacheEntity가 붙은 숫자형 ID 엔티티는 무조건 IdPool 사용
        // sequenceName은 테이블명_컬럼명_seq 패턴으로 자동 유도
        CacheEntity cacheEntityAnnotation = getEntityClass().getAnnotation(CacheEntity.class);
        if (cacheEntityAnnotation != null && isNumericIdType(this.idClass)) {
            this.allocationSize = cacheEntityAnnotation.allocationSize();
            this.sequenceName = deriveSequenceName(getEntityClass(), this.entityIdField);
            this.useIdPool = true;
        } else {
            this.sequenceName = null;
            this.allocationSize = cacheEntityAnnotation != null ? cacheEntityAnnotation.allocationSize() : 0;
            this.useIdPool = false;
        }
    }

    /**
     * 엔티티 클래스와 ID 필드로부터 PostgreSQL IDENTITY 시퀀스 이름을 자동 유도합니다.
     * 결과 형식: {table_name}_{column_name}_seq
     * (예: time_table + time_table_id → time_table_time_table_id_seq)
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

    /**
     * CamelCase 문자열을 snake_case로 변환합니다.
     */
    private static String toSnakeCase(String s) {
        return s.replaceAll("([A-Z])", "_$1").toLowerCase().replaceFirst("^_", "");
    }

    /**
     * ID 타입이 숫자형(Long, Integer 등)인지 확인합니다.
     * UUID 같은 비숫자 타입은 IdPool을 사용하지 않습니다.
     */
    private static boolean isNumericIdType(Class<?> type) {
        return Number.class.isAssignableFrom(type)
                || type == long.class || type == int.class
                || type == short.class || type == byte.class;
    }

    /**
     * 스프링 빈 초기화 후 ID Pool 등록 및 초기 할당
     */
    @PostConstruct
    private void initIdPool() {
        if (useIdPool) {
            try {
                IdPoolService idPoolService = applicationContext.getBean(IdPoolService.class);
                idPoolService.registerPool(sequenceName, allocationSize);

                // Redis에 기존 Pool이 있으면 시퀀스 리셋 없이 Redis에서 복원
                // Redis에 없을 때만 시퀀스를 현재 최대 ID로 리셋 후 새로 할당
                if (!idPoolService.isRedisPoolIntact()) {
                    try {
                        String entityName = getEntityClass().getSimpleName();
                        String idFieldName = entityIdField.getName();
                        Object result = entityManager.createQuery(
                                "SELECT MAX(e." + idFieldName + ") FROM " + entityName + " e")
                                .getSingleResult();
                        Long maxId = (result != null) ? ((Number) result).longValue() : null;
                        if (maxId != null) {
                            idPoolService.resetSequenceToMaxId(sequenceName, maxId);
                            log.info("[AutoCacheRepository] Sequence '{}' reset to current max ID={} for entity={}",
                                    sequenceName, maxId, entityName);
                        }
                    } catch (Exception e) {
                        log.warn("[AutoCacheRepository] 시퀀스 리셋 실패 (무시하고 계속): {}", e.getMessage());
                    }
                } else {
                    log.info("[AutoCacheRepository] Redis pool exists for '{}', skipping sequence reset",
                            sequenceName);
                }

                idPoolService.initializePool(sequenceName);
                log.info("[AutoCacheRepository] ID Pool initialized: entity={}, sequence={}, allocationSize={}",
                        getEntityClass().getSimpleName(), sequenceName, allocationSize);
            } catch (Exception e) {
                log.warn("[AutoCacheRepository] ID Pool 초기화 실패 (fallback to negative ID): {}", e.getMessage());
            }
        }
    }

    // ==== CacheRepository 인터페이스 기본 CRUD 구현 ====

    @Override
    public Optional<T> findById(ID id) {
        if (id != null)
            waitForLoading(id);
        DTO dto = getCacheStore().hashGet(getRedisKey(id), String.valueOf(id));
        if (dto == null) {
            return Optional.empty();
        }
        return Optional.of(convertToEntity(dto));
    }

    @Override
    public T getReferenceById(ID id) {
        return findById(id).orElseThrow(() -> new IllegalStateException("캐시에서 데이터를 찾을 수 없습니다: " + id));
    }

    @Override
    public void deleteById(ID id) {
        deleteCacheCascade(id);
    }

    @Override
    public boolean existsById(ID id) {
        return getCacheStore().hashGet(getRedisKey(id), String.valueOf(id)) != null;
    }

    @Override
    public List<T> findAllById(Iterable<ID> ids) {
        if (ids == null) {
            return Collections.emptyList();
        }
        List<String> fields = new ArrayList<>();
        ids.forEach(id -> fields.add(String.valueOf(id)));

        if (fields.isEmpty())
            return Collections.emptyList();

        List<DTO> dtos = getCacheStore().hashMutiGet(getRedisKey(null), fields);
        if (dtos == null) {
            return Collections.emptyList();
        }

        return dtos.stream()
                .filter(dto -> dto != null)
                .map(this::convertToEntity)
                .toList();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<DTO> saveAll(List<DTO> dtos) {
        for (ListIterator<DTO> iterator = dtos.listIterator(); iterator.hasNext();) {
            DTO dto = iterator.next();
            ID id = extractId(dto);
            id = changeType(id);

            if (id == null) {
                Object generatedId = null;
                if (idClass.getSimpleName().equals("Integer")) {
                    generatedId = generateId().intValue();
                } else if (idClass.getSimpleName().equals("Long")) {
                    generatedId = generateId();
                } else if (idClass.getSimpleName().equals("String")) {
                    generatedId = String.valueOf(generateId());
                } else if (idClass.getSimpleName().equals("UUID")) {
                    generatedId = java.util.UUID.randomUUID();
                }

                if (generatedId != null) {
                    dto = updateDtoWithId(dto, (ID) generatedId);
                    iterator.set(dto);
                    id = extractId(dto);
                }
            }
            String hashKey = getRedisKey(id);
            getCacheStore().hashSet(hashKey, String.valueOf(id), dto);
            removeFromDeletedSet(id);

            // 부모 ID 인덱스 추가
            for (Map.Entry<Field, Class<?>> entry : parentEntityClassMap.entrySet()) {
                try {
                    Object parentId = entry.getKey().get(dto);
                    if (parentId != null) {
                        addIdToParentIndex(hashKey, entry.getValue(), parentId, id);
                    }
                } catch (IllegalAccessException e) {
                    // ignore
                }
            }
        }
        return dtos;
    }

    @Override
    public void deleteAllById(Iterable<ID> ids) {
        if (ids == null) {
            return;
        }
        ids.forEach(this::deleteCacheCascade);
    }

    // ==== 내부 헬퍼 메서드 ====

    protected final String getRedisKey(ID id) {
        return cacheKeyPrefix + ":DATA";
    }

    /**
     * 삭제 추적용 Redis Set 키를 반환합니다.
     * 캐시에서 삭제된 영속 ID를 별도로 저장하여 동기화 시 안정적으로 DB에서 삭제합니다.
     */
    protected final String getDeletedSetKey() {
        return cacheKeyPrefix + ":DELETED";
    }

    /**
     * 삭제된 영속 ID를 추적 Set에 추가합니다.
     * 임시 ID(음수)나 null은 추적하지 않습니다.
     */
    private void trackDeletedId(ID id) {
        if (id == null)
            return;
        // 임시(음수) ID는 DB에 없으므로 추적 불필요
        if (!useIdPool && id instanceof Number number && number.longValue() < 0L) {
            return;
        }
        getCacheStore().addToSet(getDeletedSetKey(), String.valueOf(id));
    }

    /**
     * 추적된 삭제 ID 목록을 반환합니다.
     */
    public Set<String> getDeletedIds() {
        return getCacheStore().getSet(getDeletedSetKey());
    }

    /**
     * 추적된 삭제 ID 목록을 초기화합니다.
     */
    public void clearDeletedIds() {
        getCacheStore().delete(getDeletedSetKey());
    }

    /**
     * DELETED Set에서 특정 ID를 제거합니다.
     * Undo/Redo로 엔티티가 복원될 때 호출하여, 동기화 시 잘못 삭제되는 것을 방지합니다.
     */
    public void removeFromDeletedSet(ID id) {
        if (id == null)
            return;
        getCacheStore().removeFromSet(getDeletedSetKey(), String.valueOf(id));
    }

    /**
     * DELETED Set에서 특정 ID를 제거합니다 (타입 체크 없는 버전).
     */
    @SuppressWarnings("unchecked")
    public void removeFromDeletedSetUnchecked(Object id) {
        if (id == null)
            return;
        removeFromDeletedSet((ID) id);
    }

    private String getParentIndexField(Class<?> parentClass, Object parentId) {
        return "P_IDX:" + parentClass.getSimpleName() + ":" + parentId;
    }

    private void addIdToParentIndex(String hashKey, Class<?> parentClass, Object parentId, ID id) {
        if (parentId == null || parentClass == null)
            return;
        String field = getParentIndexField(parentClass, parentId);
        String idStr = String.valueOf(id);

        synchronized (this) {
            String existing = getCacheStore().hashGetString(hashKey, field);
            if (existing == null || existing.isEmpty()) {
                getCacheStore().hashSetString(hashKey, field, idStr);
            } else {
                Set<String> ids = new java.util.LinkedHashSet<>(Arrays.asList(existing.split(",")));
                if (ids.add(idStr)) {
                    getCacheStore().hashSetString(hashKey, field, String.join(",", ids));
                }
            }
        }
    }

    private void removeIdFromParentIndex(String hashKey, Class<?> parentClass, Object parentId, ID id) {
        if (parentId == null || parentClass == null)
            return;
        String field = getParentIndexField(parentClass, parentId);
        String idStr = String.valueOf(id);

        synchronized (this) {
            String existing = getCacheStore().hashGetString(hashKey, field);
            if (existing != null && !existing.isEmpty()) {
                Set<String> ids = new java.util.LinkedHashSet<>(Arrays.asList(existing.split(",")));
                if (ids.remove(idStr)) {
                    if (ids.isEmpty()) {
                        getCacheStore().hashDelete(hashKey, field);
                    } else {
                        getCacheStore().hashSetString(hashKey, field, String.join(",", ids));
                    }
                }
            }
        }
    }

    /**
     * CacheStore를 반환합니다. Redis 또는 InMemory 구현체가 사용됩니다.
     */
    @SuppressWarnings("unchecked")
    protected final CacheStore<DTO> getCacheStore() {
        // 먼저 CacheStore 빈이 있는지 확인 (인메모리 또는 커스텀)
        String cacheStoreBeanName = cacheKeyPrefix + "CacheStore";
        if (applicationContext.containsBean(cacheStoreBeanName)) {
            return (CacheStore<DTO>) applicationContext.getBean(cacheStoreBeanName);
        }

        // 글로벌 CacheStore가 있으면 사용 (InMemory 또는 커스텀)
        if (applicationContext.containsBean("globalCacheStore")) {
            return (CacheStore<DTO>) applicationContext.getBean("globalCacheStore");
        }

        // 폴백 CacheStore 확인 (Redis 없을 때 자동 생성된 InMemory)
        if (applicationContext.containsBean("fallbackCacheStore")) {
            return (CacheStore<DTO>) applicationContext.getBean("fallbackCacheStore");
        }

        // Redis 사용 - RedisTemplate을 래핑하여 반환 (하위 호환)
        try {
            return new RedisCacheStore<>(
                    (RedisTemplate<String, DTO>) applicationContext.getBean(redisTemplateBeanName));
        } catch (Exception e) {
            // RedisTemplate도 없으면 임시 InMemory 사용 (개발 편의)
            return (CacheStore<DTO>) new InMemoryCacheStore<DTO>();
        }
    }

    /**
     * @deprecated Use getCacheStore() instead
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    protected final RedisTemplate<String, DTO> getRedisTemplate() {
        return (RedisTemplate<String, DTO>) applicationContext.getBean(redisTemplateBeanName);
    }

    @SuppressWarnings("unchecked")
    protected final ID extractId(DTO dto) {
        try {
            return changeType((ID) idField.get(dto));
        } catch (IllegalAccessException e) {
            throw new RuntimeException("ID 필드에 접근할 수 없습니다: " + idField.getName(), e);
        }
    }

    protected final List<Object> extractParentIds(DTO dto) {
        if (parentIdFields.isEmpty())
            return Collections.emptyList();
        List<Object> ids = new ArrayList<>();
        for (Field field : parentIdFields) {
            try {
                Object val = field.get(dto);
                if (val != null)
                    ids.add(val);
            } catch (IllegalAccessException e) {
                // ignore
            }
        }
        return ids;
    }

    /**
     * ID가 null일 경우 ID Pool 또는 음수 임시 ID를 생성하여 저장
     */
    @SuppressWarnings("unchecked")
    public DTO save(DTO dto) {
        ID id = extractId(dto);

        // ID가 null이면 ID 생성
        if (id == null) {
            Object generatedId = null;
            if (idClass.getSimpleName().equals("UUID")) {
                generatedId = java.util.UUID.randomUUID();
            } else if (idClass.getSimpleName().equals("Long")) {
                generatedId = generateId();
            } else if (idClass.getSimpleName().equals("String")) {
                generatedId = String.valueOf(generateId());
            } else if (idClass.getSimpleName().equals("Integer")) {
                generatedId = generateId().intValue();
            } else {
                generatedId = generateId().intValue();
            }

            dto = updateDtoWithId(dto, (ID) generatedId);
            id = extractId(dto);
        }

        String hashKey = getRedisKey(id);
        getCacheStore().hashSet(hashKey, String.valueOf(id), dto);
        removeFromDeletedSet(id);

        // 부모 ID 인덱스 추가
        for (Map.Entry<Field, Class<?>> entry : parentEntityClassMap.entrySet()) {
            try {
                Object parentId = entry.getKey().get(dto);
                if (parentId != null) {
                    addIdToParentIndex(hashKey, entry.getValue(), parentId, id);
                }
            } catch (IllegalAccessException e) {
                // ignore
            }
        }

        return dto;
    }

    @SuppressWarnings("unchecked")
    public final void saveUnchecked(Object dto) {
        save((DTO) dto);
    }

    /**
     * 기존 데이터를 불러와서 null이 아닌 값만 업데이트 (ID 제외)
     */
    public DTO update(DTO dto) {
        ID id = extractId(dto);

        if (id == null) {
            throw new IllegalArgumentException("update는 ID가 필수입니다. save를 사용하세요.");
        }

        String hashKey = getRedisKey(id);
        removeFromDeletedSet(id);
        DTO existingDto = getCacheStore().hashGet(hashKey, String.valueOf(id));
        List<Object> oldParentIds = Collections.emptyList();
        if (existingDto != null) {
            oldParentIds = extractParentIds(existingDto);
            dto = mergeDto(existingDto, dto);
        }

        getCacheStore().hashSet(hashKey, String.valueOf(id), dto);

        // 부모 ID 인덱스 업데이트
        for (Map.Entry<Field, Class<?>> entry : parentEntityClassMap.entrySet()) {
            Field field = entry.getKey();
            Class<?> parentClass = entry.getValue();
            try {
                Object oldId = (existingDto != null) ? field.get(existingDto) : null;
                Object newId = field.get(dto);

                if (oldId != null && !Objects.equals(oldId, newId)) {
                    removeIdFromParentIndex(hashKey, parentClass, oldId, id);
                }
                if (newId != null && !Objects.equals(newId, oldId)) {
                    addIdToParentIndex(hashKey, parentClass, newId, id);
                }
            } catch (IllegalAccessException e) {
                // ignore
            }
        }

        return dto;
    }

    /**
     * Entity의 필드를 다른 Entity의 null이 아닌 값으로 업데이트
     * 리플렉션을 사용하여 범용적으로 처리
     *
     * @param target 업데이트할 대상 Entity
     * @param source 데이터를 가져올 소스 Entity (null이 아닌 값만 복사)
     */
    public void mergeEntityFields(T target, T source) {
        if (target == null || source == null) {
            throw new IllegalArgumentException("target과 source는 null일 수 없습니다.");
        }

        try {
            Class<?> entityClass = target.getClass();

            // 모든 필드를 순회하며 업데이트
            for (Field field : entityClass.getDeclaredFields()) {
                field.setAccessible(true);

                // ID 필드는 건너뛰기
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    continue;
                }

                // @ManyToOne, @OneToMany 등 관계 필드는 건너뛰기 (선택적)
                if (field.isAnnotationPresent(jakarta.persistence.ManyToOne.class) ||
                        field.isAnnotationPresent(jakarta.persistence.OneToMany.class) ||
                        field.isAnnotationPresent(jakarta.persistence.OneToOne.class) ||
                        field.isAnnotationPresent(jakarta.persistence.ManyToMany.class)) {

                    // 관계 필드도 null이 아니면 업데이트
                    Object sourceValue = field.get(source);
                    if (sourceValue != null) {
                        // Collection 타입인 경우 (OneToMany, ManyToMany) 기존 컬렉션을 유지하며 내용만 업데이트
                        if (sourceValue instanceof Collection<?> sourceCollection) {
                            Object targetValue = field.get(target);
                            if (targetValue instanceof Collection targetCollection
                                    && targetCollection != sourceCollection) {
                                try {
                                    targetCollection.clear();
                                    ((Collection) targetCollection).addAll(sourceCollection);
                                } catch (Exception e) {
                                    // 읽기 전용 컬렉션이거나 수정 불가한 경우 교체 시도
                                    field.set(target, sourceValue);
                                }
                                continue;
                            }
                        }
                        field.set(target, sourceValue);
                    }
                    continue;
                }

                // source의 값이 null이 아니면 target에 설정
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
     * 기존 DTO와 새 DTO를 병합
     * 새 DTO의 null이 아닌 값들로 기존 DTO를 업데이트 (ID 제외)
     */
    private DTO mergeDto(DTO existingDto, DTO newDto) {
        try {
            for (Field field : dtoFields) {
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

    /**
     * ID 생성: Pool 모드면 DB 시퀀스에서 미리 할당받은 양수 ID 반환,
     * 아니면 기존 CacheStore DECR 방식으로 음수 임시 ID 반환.
     */
    private Long generateId() {
        if (useIdPool) {
            try {
                IdPoolService idPoolService = applicationContext.getBean(IdPoolService.class);
                return idPoolService.nextId(sequenceName);
            } catch (Exception e) {
                log.warn("[AutoCacheRepository] ID Pool에서 ID 할당 실패, 음수 ID fallback: {}", e.getMessage());
            }
        }
        // fallback: 기존 음수 ID 방식
        String counterKey = "temporary:" + cacheKeyPrefix + ":counter";
        Long counter = getCacheStore().decrement(counterKey);
        return counter;
    }

    /**
     * DTO의 ID 필드를 업데이트 (Record는 새 인스턴스 생성)
     */
    @SuppressWarnings("unchecked")
    private DTO updateDtoWithId(DTO dto, ID newId) {
        try {
            idField.set(dto, newId);
            return dto;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("DTO ID 업데이트 실패: " + dto, e);
        }
    }

    @SuppressWarnings("unchecked")
    protected final T convertToEntity(DTO dto) {
        try {
            // 필요한 Repository들을 자동으로 주입해서 Entity 변환
            Object[] parameters = buildEntityConverterParameters(dto);
            return (T) entityConverterMethod.invoke(dto, parameters);
        } catch (Exception e) {
            throw new RuntimeException("Entity 변환에 실패했습니다: " + dto, e);
        }
    }

    @SuppressWarnings("unchecked")
    protected final DTO convertToDto(T entity) {
        if (entity == null) {
            return null;
        }

        try {
            Method fromEntityMethod = dtoClass.getMethod("fromEntity", getEntityClass());
            return (DTO) fromEntityMethod.invoke(null, entity);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 fromEntity 메서드가 필요합니다.", e);
        } catch (Exception e) {
            throw new RuntimeException("Entity를 DTO로 변환하는 데 실패했습니다.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<T> loadEntitiesByParentId(Object parentId) {
        return loadEntitiesByParentId(parentId, null);
    }

    private List<T> loadEntitiesByParentId(Object parentId, Class<?> parentClass) {

        if (entityManager == null) {
            return Collections.emptyList();
        }

        // 부모 필드가 있으면 Criteria로 조회
        if (!parentIdFields.isEmpty()) {
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
     * JPA Criteria API를 사용하여 parentId로 엔티티 조회
     * Repository에 메서드가 없어도 동작합니다!
     */
    @SuppressWarnings("unchecked")
    private List<T> loadEntitiesByCriteria(Object parentId) {
        return loadEntitiesByCriteria(parentId, null);
    }

    @SuppressWarnings("unchecked")
    private List<T> loadEntitiesByCriteria(Object parentId, Class<?> targetParentClass) {
        Class<T> entityClass = getEntityClass();

        if (parentEntityClassMap.isEmpty() || entityManager == null) {
            return loadAllEntitiesByCriteria();
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = (CriteriaQuery<T>) cb.createQuery(entityClass);
        Root<T> root = (Root<T>) query.from(entityClass);

        List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
        for (Class<?> parentClass : parentEntityClassMap.values()) {
            // 특정 부모 클래스가 지정된 경우 해당 클래스만 처리
            if (targetParentClass != null && !parentClass.equals(targetParentClass)) {
                continue;
            }

            // Entity 클래스 계층에서 해당 부모 타입을 가진 필드 찾기
            for (Field field : getAllFieldsInHierarchy(entityClass)) {
                if (field.getType().isAssignableFrom(parentClass)) {
                    try {
                        // 부모 엔티티의 @Id 필드 정보를 동적으로 가져옴
                        Field pIdField = locateEntityIdField(parentClass);
                        if (pIdField == null)
                            continue;

                        String idFieldName = pIdField.getName();
                        Class<?> pIdType = pIdField.getType();

                        // parentId(보통 String)를 부모 ID의 실제 타입(UUID, Integer 등)으로 변환
                        Object normalizedParentId = convertIdToType(pIdType, parentId);

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

        return entityManager.createQuery(query).getResultList();
    }

    /**
     * JPA Criteria API를 사용하여 모든 엔티티 조회 (루트 엔티티용)
     */
    @SuppressWarnings("unchecked")
    private List<T> loadAllEntitiesByCriteria() {
        Class<T> entityClass = getEntityClass();

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = (CriteriaQuery<T>) cb.createQuery(entityClass);
        query.from(entityClass);

        return entityManager.createQuery(query).getResultList();
    }

    /**
     * JPA Criteria API를 사용하여 ID로 단일 엔티티 조회
     */
    @SuppressWarnings("unchecked")
    private T loadEntityByIdCriteria(ID id) {
        Class<T> entityClass = getEntityClass();

        // Try to load entity with necessary relations eager-fetched (left join fetch)
        // based on DTO cache fields like `cacheUserId` -> relation `user`.
        if (entityManager == null) {
            return null;
        }

        try {
            List<String> relationsToFetch = new ArrayList<>();

            for (Field dtoField : dtoFields) {
                String dtoFieldName = dtoField.getName();
                if (dtoFieldName == null)
                    continue;
                if (dtoFieldName.endsWith("Id")) {
                    String entitySimple = dtoFieldName.substring(0, dtoFieldName.length() - 2); // e.g. "User"
                    if (entitySimple.isEmpty())
                        continue;
                    String candidate = Character.toLowerCase(entitySimple.charAt(0)) + entitySimple.substring(1);

                    for (Field f : getAllFieldsInHierarchy(entityClass)) {
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
                                    // DTO 필드명(예: userId)에서 추출한 candidate(user)와
                                    // JoinColumn 이름(user_id)이 유사한지 확인
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

            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<T> query = (CriteriaQuery<T>) cb.createQuery(entityClass);
            Root<T> root = (Root<T>) query.from(entityClass);

            // add fetch joins
            java.util.Set<String> uniq = new java.util.LinkedHashSet<>(relationsToFetch);
            for (String rel : uniq) {
                try {
                    root.fetch(rel, jakarta.persistence.criteria.JoinType.LEFT);
                } catch (IllegalArgumentException ignored) {
                    // ignore invalid relation names
                }
            }

            query.select(root).where(cb.equal(root.get(entityIdField.getName()), id));

            try {
                return entityManager.createQuery(query).getSingleResult();
            } catch (jakarta.persistence.NoResultException nre) {
                return null;
            }
        } catch (Exception e) {
            // Fallback to simple find() if anything goes wrong
            try {
                return entityManager.find(entityClass, id);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    @Override
    public final List<DTO> loadFromDatabaseByParentId(Object parentId) {
        return loadFromDatabaseByParentId(parentId, null);
    }

    @Override
    public final List<DTO> loadFromDatabaseByParentId(Object parentId, Class<?> parentClass) {
        // 1. DB에서 최신 데이터 로드
        List<DTO> dtos = loadEntitiesByParentId(parentId, parentClass).stream()
                .map(this::convertToDto)
                .toList();

        // 2. 기존 캐시 데이터 삭제 (인덱스 포함)
        // 캐시 갱신 목적이므로 DELETED SET 추적 없이 캐시만 삭제
        try {
            deleteCacheOnlyByParentId(parentId, parentClass);
        } catch (Exception e) {
            // ignore or log
        }

        // 3. 새 데이터 캐시에 저장
        if (!dtos.isEmpty()) {
            saveAll(dtos);
        }

        return dtos;
    }

    @SuppressWarnings("unchecked")
    public List<? extends CacheDto<?>> loadFromDatabaseByParentIdUnchecked(Object parentId) {
        return loadFromDatabaseByParentId(parentId);
    }

    @SuppressWarnings("unchecked")
    public final DTO loadFromDatabaseById(ID id) {
        id = changeType(id);

        try {
            // EntityManager.find() 사용 - Repository 필요 없음!
            T entity = loadEntityByIdCriteria(id);

            if (entity == null) {
                return null;
            }

            DTO dto = convertToDto(entity);
            save(dto); // 캐시 갱신
            return dto;

        } catch (Exception e) {
            return null;
        }
    }

    private ID changeType(Object id) {
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
     * Convert arbitrary id value to the requested target type (entity id field
     * type).
     * Supports String, Integer/int, Long/long, Short, Byte, UUID.
     */
    private Object convertIdToType(Class<?> targetType, Object idValue) {
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

    @Override
    public List<T> findByParentId(Object parentId) {
        return findByParentId(parentId, null);
    }

    public List<T> findByParentId(java.util.UUID parentId) {
        return findByParentId((Object) parentId, null);
    }

    public List<T> findByParentId(Integer parentId) {
        return findByParentId((Object) parentId, null);
    }

    @Override
    public List<T> findByParentId(Object parentId, Class<?> parentClass) {
        List<DTO> dtos = findDtosByParentId(parentId, parentClass);

        // Entity로 변환
        return dtos.stream()
                .map(this::convertToEntity)
                .toList();
    }

    /**
     * ParentId로 캐시에서 Entity 리스트 삭제
     * Redis에 저장된 해당 ParentId를 가진 모든 데이터를 삭제하고 삭제된 Entity 리스트 반환
     */
    @Override
    public List<T> deleteByParentId(Object parentId) {
        return deleteByParentId(parentId, null);
    }

    @Override
    public List<T> deleteByParentId(Object parentId, Class<?> parentClass) {
        if (parentIdFields.isEmpty()) {
            throw new UnsupportedOperationException("ParentId 필드가 없습니다.");
        }

        // 먼저 삭제할 DTO들을 조회
        List<DTO> dtosToDelete = findDtosByParentId(parentId, parentClass);

        if (dtosToDelete.isEmpty()) {
            return Collections.emptyList();
        }

        // 하위 캐시 포함 삭제
        dtosToDelete.stream()
                .map(this::extractId)
                .forEach(this::deleteCacheCascade);

        // 삭제된 Entity 리스트 반환
        return dtosToDelete.stream()
                .map(this::convertToEntity)
                .toList();
    }

    /**
     * ParentId로 캐시에서 DTO 리스트 조회
     * 캐시에 이미 저장된 DTO를 직접 반환 (Entity 변환 없음)
     */
    public List<DTO> findDtosByParentId(Object parentId) {
        return findDtosByParentId(parentId, null);
    }

    public List<DTO> findDtosByParentId(Object parentId, Class<?> parentClass) {
        if (parentIdFields.isEmpty()) {
            throw new UnsupportedOperationException("ParentId 필드가 없습니다.");
        }

        // Loading 상태면 대기 (부모 ID 기준)
        waitForLoading(parentId);

        String hashKey = getRedisKey(null);
        Set<String> allChildIds = new java.util.HashSet<>();

        if (parentClass != null) {
            String field = getParentIndexField(parentClass, parentId);
            String idListStr = getCacheStore().hashGetString(hashKey, field);
            if (idListStr != null && !idListStr.isEmpty()) {
                allChildIds.addAll(Arrays.asList(idListStr.split(",")));
            }
        } else {
            // 클래스가 지정되지 않으면 모든 부모 인덱스를 확인 (하위 호환성)
            for (Class<?> pClass : parentEntityClassMap.values()) {
                String field = getParentIndexField(pClass, parentId);
                String idListStr = getCacheStore().hashGetString(hashKey, field);
                if (idListStr != null && !idListStr.isEmpty()) {
                    allChildIds.addAll(Arrays.asList(idListStr.split(",")));
                }
            }
        }

        if (allChildIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 필요한 DTO만 Hash에서 가져오기
        List<DTO> allDtos = getCacheStore().hashMutiGet(hashKey, new ArrayList<>(allChildIds));
        if (allDtos == null) {
            return Collections.emptyList();
        }

        // parentId로 최종 필터링 (널 안전성 및 타입-유연 비교 적용)
        return allDtos.stream()
                .filter(dto -> dto != null)
                .filter(dto -> {
                    if (parentId == null)
                        return false;

                    for (Map.Entry<Field, Class<?>> entry : parentEntityClassMap.entrySet()) {
                        // 클래스가 지정된 경우 해당 클래스 필드만 확인
                        if (parentClass != null && !entry.getValue().equals(parentClass)) {
                            continue;
                        }

                        try {
                            Object dtoParentId = entry.getKey().get(dto);
                            if (dtoParentId == null)
                                continue;

                            if (parentId.getClass().isInstance(dtoParentId)
                                    || dtoParentId.getClass().isInstance(parentId)) {
                                if (Objects.equals(parentId, dtoParentId))
                                    return true;
                            }
                            if (parentId.toString().equals(dtoParentId.toString()))
                                return true;
                        } catch (IllegalAccessException e) {
                            // ignore
                        }
                    }
                    return false;
                })
                .toList();
    }

    // ==== 필드명으로 캐시 검색 (JPA 스타일) ====

    /**
     * 특정 필드명과 값으로 캐시에서 DTO 리스트 조회
     * JPA의 findByXxx 처럼 사용 가능
     * 예: findByField("cacheUserId", 1L) → cacheUserId가 1인 모든 DTO 반환
     * 
     * @param fieldName DTO의 필드명 (예: "cacheUserId", "cacheBookId", "category")
     * @param value     검색할 값
     * @return 매칭되는 DTO 리스트
     */
    public List<DTO> findByField(String fieldName, Object value) {
        if (fieldName == null || value == null) {
            return Collections.emptyList();
        }

        Field targetField = findFieldInHierarchy(dtoClass, fieldName);
        if (targetField == null) {
            return Collections.emptyList();
        }
        targetField.setAccessible(true);

        return findAllDtos().stream()
                .filter(dto -> matchesFieldValue(dto, targetField, value))
                .toList();
    }

    /**
     * 특정 필드명과 값으로 캐시에서 Entity 리스트 조회
     */
    public List<T> findEntitiesByField(String fieldName, Object value) {
        return findByField(fieldName, value).stream()
                .map(this::convertToEntity)
                .toList();
    }

    /**
     * 특정 필드명과 값으로 캐시에서 단일 DTO 조회 (첫 번째 매칭)
     */
    public Optional<DTO> findOneByField(String fieldName, Object value) {
        List<DTO> results = findByField(fieldName, value);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 특정 필드명과 값으로 캐시에서 단일 Entity 조회
     */
    public Optional<T> findOneEntityByField(String fieldName, Object value) {
        return findOneByField(fieldName, value).map(this::convertToEntity);
    }

    /**
     * 여러 필드 조건으로 캐시에서 DTO 리스트 조회 (AND 조건)
     * 예: findByFields(Map.of("cacheUserId", 1L, "category", "Reading"))
     */
    public List<DTO> findByFields(Map<String, Object> fieldValues) {
        if (fieldValues == null || fieldValues.isEmpty()) {
            return Collections.emptyList();
        }

        // 각 필드에 대한 Field 객체 미리 찾기
        Map<Field, Object> fieldMap = new java.util.HashMap<>();
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            Field field = findFieldInHierarchy(dtoClass, entry.getKey());
            if (field == null) {
                return Collections.emptyList();
            }
            field.setAccessible(true);
            fieldMap.put(field, entry.getValue());
        }

        return findAllDtos().stream()
                .filter(dto -> {
                    for (Map.Entry<Field, Object> entry : fieldMap.entrySet()) {
                        if (!matchesFieldValue(dto, entry.getKey(), entry.getValue())) {
                            return false;
                        }
                    }
                    return true;
                })
                .toList();
    }

    /**
     * 캐시에서 모든 DTO 조회
     */
    public List<DTO> findAllDtos() {
        String hashKey = getRedisKey(null);
        Set<String> fields = getCacheStore().hashkeys(hashKey);
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyList();
        }

        // 인덱스 필드(P_IDX:...) 제외하고 실제 데이터 필드만 필터링
        List<String> dataFields = fields.stream()
                .filter(f -> !f.startsWith("P_IDX:"))
                .toList();

        if (dataFields.isEmpty()) {
            return Collections.emptyList();
        }

        List<DTO> allDtos = getCacheStore().hashMutiGet(hashKey, dataFields);
        if (allDtos == null) {
            return Collections.emptyList();
        }

        return allDtos.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 캐시에서 모든 Entity 조회
     */
    public List<T> findAllEntities() {
        return findAllDtos().stream()
                .map(this::convertToEntity)
                .toList();
    }

    /**
     * DTO의 필드 값이 주어진 값과 일치하는지 확인 (타입 유연 비교)
     */
    private boolean matchesFieldValue(DTO dto, Field field, Object expectedValue) {
        try {
            Object actualValue = field.get(dto);

            if (actualValue == null && expectedValue == null) {
                return true;
            }
            if (actualValue == null || expectedValue == null) {
                return false;
            }

            // 동일 타입이면 equals 비교
            if (actualValue.getClass().equals(expectedValue.getClass())) {
                return Objects.equals(actualValue, expectedValue);
            }

            // Enum 비교: String으로 비교
            if (actualValue.getClass().isEnum() || expectedValue.getClass().isEnum()) {
                return actualValue.toString().equals(expectedValue.toString());
            }

            // 숫자 타입 비교: Long, Integer 등
            if (actualValue instanceof Number && expectedValue instanceof Number) {
                return ((Number) actualValue).longValue() == ((Number) expectedValue).longValue();
            }

            // 타입이 다르면 문자열로 비교
            return actualValue.toString().equals(expectedValue.toString());
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    /**
     * 이 DTO가 가진 모든 필드명 목록 반환 (디버그/개발용)
     */
    public List<String> getAvailableFieldNames() {
        return dtoFields.stream()
                .map(Field::getName)
                .toList();
    }

    private Object[] buildEntityConverterParameters(DTO dto) throws Exception {
        Class<?>[] parameterTypes = entityConverterMethod.getParameterTypes();
        Object[] params = new Object[parameterTypes.length];
        Type[] genericParameterTypes = entityConverterMethod.getGenericParameterTypes();

        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> paramType = parameterTypes[i];
            Type genericType = genericParameterTypes[i];

            // Determine expected entity class for this converter parameter
            Class<?> expectedEntityClass = null;
            if (List.class.isAssignableFrom(paramType)) {
                expectedEntityClass = getListElementType(genericType);
            } else {
                expectedEntityClass = paramType;
            }

            // If we have an expected entity class, use EntityManager to obtain references
            if (List.class.isAssignableFrom(paramType)) {
                Class<?> elementType = expectedEntityClass;
                if (elementType != null) {
                    List<?> idList = extractRelatedIdList(dto, elementType);
                    if (idList != null && !idList.isEmpty()) {
                        List<Object> entities = new ArrayList<>();
                        for (Object id : idList) {
                            try {
                                Object normalizedId = changeType((ID) id);
                                Object ref = entityManager.getReference(elementType, normalizedId);
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
                                // Find id field type for the expected entity and convert accordingly
                                Field relatedIdField = locateEntityIdField(expectedEntityClass);
                                Class<?> relatedIdType = relatedIdField != null ? relatedIdField.getType() : null;
                                Object normalized = convertIdToType(relatedIdType, relatedId);
                                Object ref = entityManager.getReference(expectedEntityClass, normalized);
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

    /**
     * 제네릭 타입에서 List의 요소 타입 추출
     */
    private Class<?> getListElementType(Type genericType) {
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
     * DTO에서 관련 ID 리스트 추출 (List<Tag> 등을 위해)
     */
    @SuppressWarnings("unchecked")
    private List<?> extractRelatedIdList(DTO dto, Class<?> elementType) {
        String tableName = getTableName(elementType);
        for (Field field : getAllFieldsInHierarchy(dtoClass)) {
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
            // Determine the expected entity class from the converter method parameter
            Class<?>[] paramTypes = entityConverterMethod.getParameterTypes();
            Type[] genericParamTypes = entityConverterMethod.getGenericParameterTypes();
            Class<?> entityClass = null;
            if (parameterIndex < paramTypes.length) {
                Class<?> paramType = paramTypes[parameterIndex];
                if (List.class.isAssignableFrom(paramType)) {
                    entityClass = getListElementType(genericParamTypes[parameterIndex]);
                } else {
                    entityClass = paramType;
                }
            }

            if (entityClass == null) {
                return null;
            }

            // 0순위: @TableName 어노테이션 매칭 (테이블 이름 기반)
            String tableName = getTableName(entityClass);
            for (Field field : getAllFieldsInHierarchy(dtoClass)) {
                TableName tableNameAnnotation = field.getAnnotation(TableName.class);
                if (tableNameAnnotation != null && tableNameAnnotation.value().equalsIgnoreCase(tableName)) {
                    field.setAccessible(true);
                    Object val = field.get(dto);
                    if (val != null)
                        return val;
                }
            }

            // 1순위: DTO에서 @ParentId(entityClass)가 붙은 필드 찾기
            for (Field field : getAllFieldsInHierarchy(dtoClass)) {
                field.setAccessible(true);

                // @ParentId 어노테이션 확인 - 엔티티 클래스와 일치하는지
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

    /**
     * 클래스 계층에서 모든 필드 가져오기
     */
    private List<Field> getAllFieldsInHierarchy(Class<?> clazz) {
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

    /**
     * 엔티티 클래스에서 테이블 이름을 가져옵니다.
     * 
     * @Table 어노테이션이 있으면 해당 이름을 사용하고, 없으면 클래스 이름을 사용합니다.
     */
    private String getTableName(Class<?> entityClass) {
        jakarta.persistence.Table table = entityClass.getAnnotation(jakarta.persistence.Table.class);
        if (table != null && !table.name().isEmpty()) {
            return table.name();
        }
        return entityClass.getSimpleName();
    }

    @Override
    public boolean isLoading(Object id) {
        if (id == null)
            return false;
        try {
            PresenceStorage presenceStorage = applicationContext.getBean(PresenceStorage.class);
            return presenceStorage.isLoading(id.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForLoading(Object id) {
        if (id == null)
            return;

        try {
            PresenceStorage presenceStorage = applicationContext.getBean(PresenceStorage.class);
            // 최대 5초 대기 (500ms * 10회)
            for (int i = 0; i < 10; i++) {
                if (!presenceStorage.isLoading(id.toString())) {
                    return;
                }
                if (i % 2 == 0) {
                    log.debug("[AutoCacheRepository] Waiting for loading... id={}", id);
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
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

    @SuppressWarnings("unchecked")
    private Class<T> getEntityClass() {
        Type superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType) {
            Type[] typeArgs = ((ParameterizedType) superClass).getActualTypeArguments();
            return (Class<T>) typeArgs[0]; // Entity는 첫 번째 타입 파라미터
        }
        throw new IllegalStateException("Entity 클래스를 추출할 수 없습니다.");
    }

    private Field findFieldWithAnnotation(Class<?> clazz,
            Class<? extends java.lang.annotation.Annotation> annotationClass) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(annotationClass)) {
                return field;
            }
        }
        return null;
    }

    private Method findMethodWithAnnotation(Class<?> clazz,
            Class<? extends java.lang.annotation.Annotation> annotationClass) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotationClass)) {
                return method;
            }
        }
        return null;
    }

    private Field locateEntityIdField(Class<?> entityClass) {
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

    public DTO findDtoById(ID id) {
        return getCacheStore().hashGet(getRedisKey(id), String.valueOf(id));
    }

    public List<DTO> findDtoListByParentId(ID parentId) {
        return findDtosByParentId(parentId);
    }

    @SuppressWarnings("unchecked")
    public DTO findDtoByIdUnchecked(Object id) {
        return findDtoById((ID) id);
    }

    @SuppressWarnings("unchecked")
    public List<DTO> findDtoListByParentIdUnchecked(Object parentId) {
        return findDtoListByParentId((ID) parentId);
    }

    public void deleteCacheById(ID id) {
        if (id == null) {
            return;
        }
        deleteCacheCascade(id);
    }

    public void deleteCacheByParentId(ID parentId) {
        if (parentIdFields.isEmpty() || parentId == null) {
            return;
        }
        removeEntriesByParentInternal(parentId);
    }

    @SuppressWarnings("unchecked")
    public void deleteCacheByParentIdUnchecked(Object parentId) {
        if (parentId == null) {
            return;
        }
        deleteCacheByParentId((ID) parentId);
    }

    @SuppressWarnings("unchecked")
    public void deleteCacheByIdUnchecked(Object id) {
        if (id == null) {
            return;
        }
        deleteCacheById((ID) id);
    }

    /**
     * 캐시에서만 삭제 (DELETED SET 추적 없음).
     * DB에서 다시 로딩하여 캐시를 갱신하거나, 동기화 후 캐시를 정리할 때 사용.
     */
    public void deleteCacheOnlyById(ID id) {
        if (id == null) {
            return;
        }
        deleteCacheOnlyCascade(id);
    }

    /**
     * 캐시에서만 삭제 (타입 체크 없는 버전).
     */
    @SuppressWarnings("unchecked")
    public void deleteCacheOnlyByIdUnchecked(Object id) {
        if (id == null) {
            return;
        }
        deleteCacheOnlyById((ID) id);
    }

    private void deleteCacheCascade(ID id) {
        if (id == null) {
            return;
        }

        // 삭제 추적: 영속 ID를 DELETED Set에 기록 (동기화 시 DB에서 삭제할 대상)
        trackDeletedId(id);

        String hashKey = getRedisKey(id);
        // 부모 인덱스에서 제거를 위해 DTO 조회
        DTO dto = getCacheStore().hashGet(hashKey, String.valueOf(id));
        if (dto != null) {
            for (Map.Entry<Field, Class<?>> entry : parentEntityClassMap.entrySet()) {
                try {
                    Object parentId = entry.getKey().get(dto);
                    if (parentId != null) {
                        removeIdFromParentIndex(hashKey, entry.getValue(), parentId, id);
                    }
                } catch (IllegalAccessException e) {
                    // ignore
                }
            }
        }

        propagateParentDeletion(id);
        getCacheStore().hashDelete(hashKey, String.valueOf(id));
    }

    /**
     * 캐시에서만 삭제 (DELETED SET 추적 없음).
     * DB에서 다시 로딩하여 캐시를 갱신할 때 사용.
     * trackDeletedId를 호출하지 않으므로 동기화 시 DB 데이터가 잘못 삭제되지 않음.
     */
    private void deleteCacheOnlyCascade(ID id) {
        if (id == null) {
            return;
        }

        String hashKey = getRedisKey(id);
        DTO dto = getCacheStore().hashGet(hashKey, String.valueOf(id));
        if (dto != null) {
            for (Map.Entry<Field, Class<?>> entry : parentEntityClassMap.entrySet()) {
                try {
                    Object parentId = entry.getKey().get(dto);
                    if (parentId != null) {
                        removeIdFromParentIndex(hashKey, entry.getValue(), parentId, id);
                    }
                } catch (IllegalAccessException e) {
                    // ignore
                }
            }
        }

        propagateCacheOnlyParentDeletion(id);
        getCacheStore().hashDelete(hashKey, String.valueOf(id));
    }

    /**
     * 자식 엔티티를 캐시에서만 삭제 (DELETED SET 추적 없음).
     */
    @SuppressWarnings("unchecked")
    private void propagateCacheOnlyParentDeletion(Object parentIdObject) {
        if (parentIdObject == null) {
            return;
        }

        Map<String, AutoCacheRepository<?, ?, ?>> repositories = (Map<String, AutoCacheRepository<?, ?, ?>>) (Map<?, ?>) applicationContext
                .getBeansOfType(AutoCacheRepository.class);
        Class<T> entityClass = getEntityClass();

        for (AutoCacheRepository<?, ?, ?> repository : repositories.values()) {
            if (repository == this) {
                continue;
            }
            if (repository.parentEntityClassMap.isEmpty()) {
                continue;
            }
            for (Class<?> parentClass : repository.parentEntityClassMap.values()) {
                if (parentClass.isAssignableFrom(entityClass)) {
                    repository.removeCacheOnlyEntriesByParentInternal(parentIdObject, parentClass);
                }
            }
        }
    }

    /**
     * 부모 ID에 해당하는 자식 엔티티를 캐시에서만 삭제 (DELETED SET 추적 없음).
     */
    @SuppressWarnings("unchecked")
    private <PID> void removeCacheOnlyEntriesByParentInternal(Object parentIdObject, Class<?> parentClass) {
        PID parentId = (PID) parentIdObject;
        List<DTO> childDtos = findDtosByParentId(parentId, parentClass);
        childDtos.stream()
                .map(this::extractId)
                .forEach(this::deleteCacheOnlyCascade);
    }

    /**
     * ParentId로 캐시에서만 삭제 (DELETED SET 추적 없음).
     * DB에서 다시 로딩하여 캐시를 갱신할 때 사용.
     */
    public void deleteCacheOnlyByParentId(Object parentId, Class<?> parentClass) {
        if (parentIdFields.isEmpty()) {
            return;
        }

        List<DTO> dtosToDelete = findDtosByParentId(parentId, parentClass);
        if (dtosToDelete.isEmpty()) {
            return;
        }

        dtosToDelete.stream()
                .map(this::extractId)
                .forEach(this::deleteCacheOnlyCascade);
    }

    /**
     * 특정 엔티티 ID를 삭제할 때 함께 삭제될 모든 자식 엔티티들의 히스토리를 수집합니다.
     */
    @SuppressWarnings("unchecked")
    public List<HistoryAction> collectCascadedHistory(ID id) {
        if (id == null) {
            return Collections.emptyList();
        }

        List<HistoryAction> cascadedActions = new ArrayList<>();
        Map<String, AutoCacheRepository<?, ?, ?>> repositories = (Map<String, AutoCacheRepository<?, ?, ?>>) (Map<?, ?>) applicationContext
                .getBeansOfType(AutoCacheRepository.class);
        Class<T> entityClass = getEntityClass();

        for (AutoCacheRepository<?, ?, ?> repository : repositories.values()) {
            if (repository == this) {
                continue;
            }
            if (repository.parentEntityClassMap.isEmpty()) {
                continue;
            }
            for (Class<?> parentClass : repository.parentEntityClassMap.values()) {
                if (parentClass.isAssignableFrom(entityClass)) {
                    List<?> childDtos = repository.findDtosByParentIdUnchecked(id, parentClass);
                    if (!childDtos.isEmpty()) {
                        HistoryAction childAction = HistoryAction.builder()
                                .type(HistoryAction.Type.DELETE)
                                .entityName(repository.cacheKeyPrefix)
                                .dtoClassName(repository.dtoClass.getName())
                                .beforeData((List<? extends CacheDto<?>>) childDtos)
                                .afterData(null)
                                .subActions(new ArrayList<>())
                                .build();

                        // 각 자식 DTO에 대해 재귀적으로 수집
                        for (Object childDto : childDtos) {
                            Object childId = repository.extractIdFromDtoUnchecked(childDto);
                            childAction.getSubActions().addAll(repository.collectCascadedHistoryUnchecked(childId));
                        }
                        cascadedActions.add(childAction);
                    }
                }
            }
        }
        return cascadedActions;
    }

    @SuppressWarnings("unchecked")
    public ID extractIdFromDtoUnchecked(Object dto) {
        return extractId((DTO) dto);
    }

    @SuppressWarnings("unchecked")
    public List<HistoryAction> collectCascadedHistoryUnchecked(Object id) {
        return collectCascadedHistory((ID) id);
    }

    @SuppressWarnings("unchecked")
    public List<DTO> findDtosByParentIdUnchecked(Object parentId, Class<?> parentClass) {
        return findDtosByParentId((ID) parentId, parentClass);
    }

    @SuppressWarnings("unchecked")
    private void propagateParentDeletion(Object parentIdObject) {
        if (parentIdObject == null) {
            return;
        }

        Map<String, AutoCacheRepository<?, ?, ?>> repositories = (Map<String, AutoCacheRepository<?, ?, ?>>) (Map<?, ?>) applicationContext
                .getBeansOfType(AutoCacheRepository.class);
        Class<T> entityClass = getEntityClass();

        for (AutoCacheRepository<?, ?, ?> repository : repositories.values()) {
            if (repository == this) {
                continue;
            }
            if (repository.parentEntityClassMap.isEmpty()) {
                continue;
            }
            for (Class<?> parentClass : repository.parentEntityClassMap.values()) {
                if (parentClass.isAssignableFrom(entityClass)) {
                    repository.removeEntriesByParentInternal(parentIdObject, parentClass);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void removeEntriesByParentInternal(Object parentIdObject) {
        if (parentIdObject == null)
            return;
        for (Class<?> parentClass : parentEntityClassMap.values()) {
            removeEntriesByParentInternal(parentIdObject, parentClass);
        }
    }

    @SuppressWarnings("unchecked")
    private void removeEntriesByParentInternal(Object parentIdObject, Class<?> parentClass) {
        if (parentIdFields.isEmpty() || parentIdObject == null) {
            return;
        }

        ID parentId = (ID) parentIdObject;
        List<DTO> dtos = findDtosByParentId(parentId, parentClass);
        if (dtos.isEmpty()) {
            return;
        }

        for (DTO dto : dtos) {
            ID childId = extractId(dto);
            deleteCacheCascade(childId);
        }
    }

    @SuppressWarnings("unchecked")
    private void syncToDatabaseByParentIdInternal(Object parentIdObject, Class<?> parentClass) {
        if (parentIdFields.isEmpty() || parentIdObject == null) {
            return;
        }

        ID parentId = (ID) parentIdObject;
        syncToDatabaseByParentId(parentId, parentClass);
    }

    // ==== 동기화 메소드 ====

    public static void syncHierarchyToDatabaseByRootId(int rootId) {
        //
    }

    public static void syncHierarchyToDatabaseByRootId(String rootId) {

    }

    public DTO syncToDatabaseByDto(DTO dto) {
        if (dto == null) {
            return null;
        }
        // 부모가 없을 때
        if (parentIdFields.isEmpty()) {
            return saveToDatabase(dto);
        }
        // 부모가 있을 때
        Object parentIdValue = getParentIdValue(dto);
        if (parentIdValue instanceof Number number && number.longValue() < 0) {
            return null;
        }
        return saveToDatabase(dto);
    }

    @SuppressWarnings("unchecked")
    public DTO syncToDatabaseByDtoUnchecked(Object dto) {
        return syncToDatabaseByDto((DTO) dto);
    }

    /**
     * 캐시에 존재하는 ParentId 하위 DTO들을 DB와 동기화하며, 캐시에 없어진 엔티티는 DB에서도 삭제합니다.
     */
    public List<DTO> syncToDatabaseByParentId(Object parentId) {
        return syncToDatabaseByParentId(parentId, null);
    }

    public List<DTO> syncToDatabaseByParentId(Object parentId, Class<?> parentClass) {
        if (parentIdFields.isEmpty()) {
            throw new UnsupportedOperationException("ParentId 필드가 없습니다.");
        }
        if (parentId == null) {
            return Collections.emptyList();
        }
        if (parentId instanceof Number number && number.longValue() < 0L) {
            return Collections.emptyList(); // 아직 영속화되지 않은 부모
        }

        List<DTO> cachedDtos = findDtosByParentId(parentId, parentClass);
        if (!cachedDtos.isEmpty()) {
            cachedDtos.forEach(this::syncToDatabaseByDto);
        }

        List<DTO> refreshedDtos = findDtosByParentId(parentId, parentClass);
        Set<ID> cachedPersistentIds = refreshedDtos.stream()
                .map(this::extractId)
                .filter(Objects::nonNull)
                .filter(id -> !isTemporaryId(id))
                .collect(Collectors.toSet());

        List<T> persistedEntities = loadEntitiesByParentId(parentId, parentClass);
        if (persistedEntities == null || persistedEntities.isEmpty()) {
            return Collections.emptyList();
        }

        // CRITICAL SAFEGUARD: If cache is empty but DB has data, DO NOT WIPE DB.
        // This prevents race conditions where an empty cache (due to load
        // failure/delay) causes data loss.
        if (cachedPersistentIds.isEmpty()) {
            log.error(
                    "[CacheRepository] [TRACE-F5] CRITICAL: Sync found EMPTY CACHE for parentId={} but DB has {} entities. Aborting delete to prevent wipeout.",
                    parentId, persistedEntities.size());
            return refreshedDtos;
        }

        log.info(
                "[CacheRepository] [TRACE-F5] ParentId={}: Cache has {} persistent items, DB has {} items. Proceeding with sync.",
                parentId, cachedPersistentIds.size(), persistedEntities.size());

        List<T> entitiesToDelete = persistedEntities.stream()
                .filter(entity -> {
                    ID entityId = extractEntityId(entity);
                    return entityId != null && !cachedPersistentIds.contains(entityId);
                })
                .collect(Collectors.toList());

        if (!entitiesToDelete.isEmpty()) {
            handleChildCleanupBeforeDelete(entitiesToDelete);
            deleteAllEntities(entitiesToDelete);
        }
        return refreshedDtos;
    }

    @SuppressWarnings("unchecked")
    public List<DTO> syncToDatabaseByParentIdUnchecked(Object parentId) {
        return syncToDatabaseByParentId((ID) parentId);
    }

    @SuppressWarnings("unchecked")
    public void deleteEntitiesNotInCache(Object parentId, Set<Object> persistentIds) {
        if (parentIdFields.isEmpty() || parentId == null) {
            return;
        }

        boolean typeMatched = false;
        for (Field field : parentIdFields) {
            if (field.getType().isInstance(parentId)) {
                typeMatched = true;
                break;
            }
        }
        if (!typeMatched)
            return;

        // 안전 장치: 캐시가 완전히 비어있을 경우 (persistentIds가 비어있음)
        // 혹시 모를 레이스 컨디션으로 인한 데이터 전량 삭제를 방지하기 위해 로그를 남기고
        // 상위 호출자(CacheSyncService)에서 이미 hasTracker 체크를 하도록 함.
        // 안전 장치: 캐시가 완전히 비어있을 경우 (persistentIds가 비어있음)
        // 레이스 컨디션으로 인해 캐시가 비워진 상태에서 DB 동기화가 일어나면 데이터가 전량 삭제됨.
        // 이를 방지하기 위해 캐시가 비어있다면 삭제를 아예 수행하지 않도록 방어 로직 적용.
        if (persistentIds == null || persistentIds.isEmpty()) {
            log.error(
                    "[CacheRepository] CRITICAL: Attempt to delete ALL entities for parentId={} from DB. Aborting deletion to prevent data loss.",
                    parentId);
            return;
        }

        ID typedParentId = (ID) parentId;
        List<T> persistedEntities = loadEntitiesByParentId(typedParentId);
        if (persistedEntities == null || persistedEntities.isEmpty()) {
            return;
        }

        Set<ID> allowedIds = persistentIds == null ? Collections.emptySet()
                : persistentIds.stream()
                        .filter(Objects::nonNull)
                        .filter(id -> entityIdField.getType().isInstance(id))
                        .map(id -> (ID) id)
                        .collect(Collectors.toSet());

        List<T> targets = persistedEntities.stream()
                .filter(entity -> {
                    ID entityId = extractEntityId(entity);
                    return entityId != null && !allowedIds.contains(entityId);
                })
                .collect(Collectors.toList());

        if (targets.isEmpty()) {
            return;
        }

        handleChildCleanupBeforeDelete(targets);
        deleteAllEntities(targets);
    }

    /**
     * 삭제 추적 Set(DELETED)에 기록된 ID들을 기반으로 DB에서 엔티티를 삭제합니다.
     * 비교 기반 삭제(deleteEntitiesNotInCache)보다 안정적입니다.
     * - 명시적으로 삭제된 항목만 DB에서 제거
     * - 캐시 손실/레이스 컨디션에 의한 오삭제 방지
     */
    @SuppressWarnings("unchecked")
    public void deleteEntitiesByDeletedSet() {
        Set<String> deletedIdStrings = getDeletedIds();
        if (deletedIdStrings == null || deletedIdStrings.isEmpty()) {
            return;
        }

        log.info("[CacheRepository] Processing deleted set: entity={}, count={}, ids={}",
                cacheKeyPrefix, deletedIdStrings.size(), deletedIdStrings);

        List<T> entitiesToDelete = new ArrayList<>();

        for (String idStr : deletedIdStrings) {
            try {
                ID typedId = convertStringToId(idStr);
                if (typedId == null)
                    continue;

                T entity = (T) entityManager.find(getEntityClass(), typedId);
                if (entity != null) {
                    entitiesToDelete.add(entity);
                }
            } catch (Exception e) {
                log.warn("[CacheRepository] Failed to find entity for deleted id={}: {}", idStr, e.getMessage());
            }
        }

        if (!entitiesToDelete.isEmpty()) {
            handleChildCleanupBeforeDelete(entitiesToDelete);
            deleteAllEntities(entitiesToDelete);
            log.info("[CacheRepository] Deleted {} entities from DB by tracked set for entity={}",
                    entitiesToDelete.size(), cacheKeyPrefix);
        }

        // 처리 완료 후 삭제 추적 Set 초기화
        clearDeletedIds();
    }

    @SuppressWarnings("unchecked")
    private void handleChildCleanupBeforeDelete(List<T> entitiesToDelete) {
        if (entitiesToDelete == null || entitiesToDelete.isEmpty()) {
            return;
        }

        Map<String, AutoCacheRepository<?, ?, ?>> repositories = (Map<String, AutoCacheRepository<?, ?, ?>>) (Map<?, ?>) applicationContext
                .getBeansOfType(AutoCacheRepository.class);
        Class<T> entityClass = getEntityClass();

        for (T entity : entitiesToDelete) {
            ID parentId = extractEntityId(entity);
            if (parentId == null) {
                continue;
            }

            for (AutoCacheRepository<?, ?, ?> repository : repositories.values()) {
                if (repository.parentEntityClassMap.isEmpty()) {
                    continue;
                }
                for (Class<?> parentClass : repository.parentEntityClassMap.values()) {
                    if (parentClass.isAssignableFrom(entityClass)) {
                        repository.syncToDatabaseByParentIdInternal(parentId, parentClass);
                        repository.removeEntriesByParentInternal(parentId, parentClass);
                    }
                }
            }
        }
    }

    @SuppressWarnings("null")
    private DTO saveToDatabase(DTO dto) {
        T entity = convertToEntity(dto);

        ID previousId = extractEntityId(entity);
        boolean hasPersistentId = previousId != null && !isTemporaryId(previousId);

        // Pool 모드: ID가 있지만 DB에 아직 존재하지 않을 수 있음
        boolean isPooledNewEntity = false;
        if (useIdPool && hasPersistentId) {
            // DB에 실제 존재하는지 확인
            T existing = entityManager.find(getEntityClass(), previousId);
            if (existing == null) {
                isPooledNewEntity = true;
            }
        }

        if (!hasPersistentId) {
            setEntityId(entity, null);
        }

        T entityToSave = entity;
        if (hasPersistentId && !isPooledNewEntity) {
            ID persistedId = Objects.requireNonNull(previousId);
            T origin = entityManager.find(getEntityClass(), persistedId);
            if (origin != null) {
                mergeEntityFields(origin, entity);
                entityToSave = origin;
            }
        }

        // EntityManager로 저장 (persist 또는 merge)
        // 방어적 검사: 필수 ManyToOne 관계가 null이면 저장을 건너뜀
        Class<?> entityClazz = getEntityClass();
        try {
            for (java.lang.reflect.Field f : entityClazz.getDeclaredFields()) {
                if (f.isAnnotationPresent(jakarta.persistence.ManyToOne.class)) {
                    jakarta.persistence.JoinColumn jc = f.getAnnotation(jakarta.persistence.JoinColumn.class);
                    boolean nullable = true;
                    if (jc != null) {
                        nullable = jc.nullable();
                    }
                    if (!nullable) {
                        f.setAccessible(true);
                        Object val = f.get(entityToSave);
                        if (val == null) {
                            System.err.println(
                                    "[SharedSync][WARN] Required ManyToOne relation is null - skipping DB save: "
                                            + entityClazz.getSimpleName() + "." + f.getName());
                            return dto; // skip saving to avoid FK violation
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[SharedSync][WARN] failed to validate required relations: " + e.getMessage());
        }

        T savedEntity;
        if (isPooledNewEntity) {
            // Pool ID로 직접 INSERT (OVERRIDING SYSTEM VALUE)
            savedEntity = insertWithOverridingSystemValue(entityToSave);
        } else {
            savedEntity = saveEntity(entityToSave);
        }

        DTO updatedDto = convertToDto(savedEntity);
        ID cacheId = extractId(updatedDto);

        if (cacheId != null) {
            String cacheKey = getRedisKey(cacheId);
            DTO dtoToCache = Objects.requireNonNull(updatedDto);
            getCacheStore().hashSet(cacheKey, String.valueOf(cacheId), dtoToCache);
        }

        // 새로 영속화된 ID를 모든 하위 캐시에 전파 (레거시 음수 ID 모드)
        if (isTemporaryId(previousId) && !isTemporaryId(cacheId)) {
            propagateParentIdChange(previousId, cacheId);
        }
        if (previousId != null && !Objects.equals(previousId, cacheId)) {
            String staleKey = getRedisKey(previousId);
            getCacheStore().hashDelete(staleKey, String.valueOf(previousId));

            // 부모 인덱스에서 이전 ID 제거하고 새 ID 추가
            for (Map.Entry<Field, Class<?>> entry : parentEntityClassMap.entrySet()) {
                try {
                    Object parentId = entry.getKey().get(updatedDto);
                    if (parentId != null) {
                        removeIdFromParentIndex(staleKey, entry.getValue(), parentId, previousId);
                        addIdToParentIndex(getRedisKey(cacheId), entry.getValue(), parentId, cacheId);
                    }
                } catch (IllegalAccessException e) {
                    // ignore
                }
            }
        }
        return updatedDto;
    }

    /**
     * Pool ID를 가진 새 엔티티를 DB에 INSERT합니다.
     * PostgreSQL의 GENERATED ALWAYS AS IDENTITY 컬럼에 명시적 ID를 넣기 위해
     * OVERRIDING SYSTEM VALUE 구문을 사용합니다.
     */
    @SuppressWarnings("unchecked")
    private T insertWithOverridingSystemValue(T entity) {
        Class<?> entityClass = getEntityClass();
        String tableName = getTableName(entityClass);

        List<String> columnNames = new ArrayList<>();
        List<Object> columnValues = new ArrayList<>();

        // 엔티티의 모든 필드를 순회하며 컬럼 매핑 정보 추출
        for (Field field : getAllPersistableFields(entityClass)) {
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
            jakarta.persistence.Query query = entityManager.createNativeQuery(sql.toString());
            for (int i = 0; i < columnValues.size(); i++) {
                query.setParameter(i + 1, columnValues.get(i));
            }
            query.executeUpdate();
            log.info("[AutoCacheRepository] Inserted entity with Pool ID: table={}, id={}",
                    tableName, extractEntityId(entity));
        } catch (Exception e) {
            log.error("[AutoCacheRepository] Native INSERT failed for {}: {}",
                    entityClass.getSimpleName(), e.getMessage());
            // Native INSERT 실패 시 fallback: 기존 persist 방식 시도
            // 트랜잭션이 깨진 상태이므로, 현재 엔티티를 detach 후 새 persist 시도는 불가능.
            // DB에 저장되지 않았지만 캐시에는 유지되므로, 다음 주기적 동기화에서 재시도됩니다.
            log.warn("[AutoCacheRepository] Entity will be retried on next periodic sync. id={}",
                    extractEntityId(entity));
        }

        return entity;
    }

    /**
     * 엔티티 클래스(상속 포함)의 모든 영속 가능한 필드를 반환합니다.
     */
    private List<Field> getAllPersistableFields(Class<?> clazz) {
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

    /**
     * 연관 엔티티에서 @Id 필드의 값을 추출합니다.
     * Hibernate 프록시 객체도 지원합니다.
     */
    private Object extractIdFromRelatedEntity(Object relatedEntity) {
        if (relatedEntity == null)
            return null;

        // Hibernate 프록시인 경우 실제 클래스 가져오기
        Class<?> clazz = relatedEntity.getClass();
        try {
            // org.hibernate.proxy.HibernateProxy 체크
            if (relatedEntity instanceof org.hibernate.proxy.HibernateProxy proxy) {
                // LazyInitializer에서 identifier 직접 추출
                Object identifier = proxy.getHibernateLazyInitializer().getIdentifier();
                if (identifier != null) {
                    return identifier;
                }
                // 실제 엔티티 클래스로 전환
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
     * 필드의 DB 컬럼 이름을 반환합니다.
     * 
     * @Column 어노테이션이 있으면 그 이름을, 없으면 camelCase → snake_case 변환합니다.
     */
    private String getColumnName(Field field) {
        jakarta.persistence.Column column = field.getAnnotation(jakarta.persistence.Column.class);
        if (column != null && !column.name().isEmpty()) {
            return column.name();
        }
        // @Id 필드: JPA 기본 매핑 (camelCase → snake_case)
        if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
            jakarta.persistence.Column idColumn = field.getAnnotation(jakarta.persistence.Column.class);
            if (idColumn != null && !idColumn.name().isEmpty()) {
                return idColumn.name();
            }
        }
        // camelCase → snake_case 변환
        return camelToSnake(field.getName());
    }

    /**
     * camelCase를 snake_case로 변환합니다.
     */
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

    /**
     * EntityManager를 사용하여 엔티티 저장 (persist 또는 merge)
     */
    private T saveEntity(T entity) {
        ID id = extractEntityId(entity);
        if (id == null) {
            // 새 엔티티 - persist
            entityManager.persist(entity);
            return entity;
        } else {
            // 기존 엔티티 - merge
            try {
                // @IgnoreShared 필드가 있다면 DB의 기존 값을 유지하도록 병합 전 복사
                if (!ignoredEntityFields.isEmpty()) {
                    T existing = entityManager.find(getEntityClass(), id);
                    if (existing != null) {
                        for (Field f : ignoredEntityFields) {
                            try {
                                Object val = f.get(existing);
                                f.set(entity, val);
                            } catch (IllegalAccessException e) {
                                // ignore
                            }
                        }
                    }
                }
                return entityManager.merge(entity);
            } catch (jakarta.persistence.OptimisticLockException | org.hibernate.StaleObjectStateException e) {
                // 이미 다른 트랜잭션에 의해 수정/삭제된 경우 무시
                return entity;
            }
        }
    }

    /**
     * EntityManager를 사용하여 여러 엔티티 삭제
     */
    private void deleteAllEntities(List<T> entities) {
        for (T entity : entities) {
            try {
                T managed = entityManager.contains(entity) ? entity : entityManager.merge(entity);
                entityManager.remove(managed);
            } catch (jakarta.persistence.OptimisticLockException | org.hibernate.StaleObjectStateException e) {
                // 이미 삭제된 경우 무시
            }
        }
    }

    public boolean isParentIdFieldPresent() {
        return !parentIdFields.isEmpty();
    }

    public boolean isParentEntityOf(Class<?> potentialParentEntity) {
        for (Class<?> parentClass : parentEntityClassMap.values()) {
            if (parentClass.isAssignableFrom(potentialParentEntity)) {
                return true;
            }
        }
        return false;
    }

    public Class<?> getEntityType() {
        return getEntityClass();
    }

    @SuppressWarnings("unchecked")
    public Object extractIdUnchecked(Object dto) {
        return extractId((DTO) dto);
    }

    public boolean isPersistentId(Object id) {
        return id != null && !isTemporaryId(id);
    }

    private Object getParentIdValue(DTO dto) {
        if (parentIdFields.isEmpty()) {
            return null;
        }
        try {
            // 첫 번째 non-null 부모 ID 반환
            for (Field field : parentIdFields) {
                Object val = field.get(dto);
                if (val != null)
                    return val;
            }
            return null;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("ParentId 필드 접근 실패", e);
        }
    }

    private boolean isTemporaryId(Object id) {
        if (id == null) {
            return false;
        }
        // Pool 모드에서는 항상 양수 ID → 임시 ID가 존재하지 않음
        if (useIdPool) {
            return false;
        }
        if (id instanceof Number number) {
            return number.longValue() < 0L;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void propagateParentIdChange(ID temporaryParentId, ID persistedParentId) {
        if (temporaryParentId == null || persistedParentId == null) {
            return;
        }

        Map<String, AutoCacheRepository<?, ?, ?>> repositories = (Map<String, AutoCacheRepository<?, ?, ?>>) (Map<?, ?>) applicationContext
                .getBeansOfType(AutoCacheRepository.class);
        Class<T> entityClass = getEntityClass();
        for (AutoCacheRepository<?, ?, ?> repository : repositories.values()) {
            if (repository == this) {
                continue;
            }
            if (repository.parentEntityClassMap.isEmpty()) {
                continue;
            }
            boolean isParent = false;
            for (Class<?> parentClass : repository.parentEntityClassMap.values()) {
                if (parentClass.isAssignableFrom(entityClass)) {
                    isParent = true;
                    break;
                }
            }
            if (!isParent) {
                continue;
            }
            repository.updateParentReferenceInternal(temporaryParentId, persistedParentId);
        }
    }

    @SuppressWarnings("null")
    private void updateParentReferenceInternal(Object oldParentId, Object newParentId) {
        if (parentIdFields.isEmpty()) {
            return;
        }
        if (oldParentId == null || newParentId == null) {
            return;
        }

        for (Field field : parentIdFields) {
            if (field.getType().isInstance(oldParentId) && field.getType().isInstance(newParentId)) {
                // Hash에서 해당 부모를 가진 ID 목록 가져오기 (인덱스 활용)
                String hashKey = getRedisKey(null);
                Class<?> parentClass = parentEntityClassMap.get(field);
                if (parentClass == null)
                    continue;

                String oldIndexField = getParentIndexField(parentClass, oldParentId);
                String idListStr = getCacheStore().hashGetString(hashKey, oldIndexField);

                if (idListStr == null || idListStr.isEmpty()) {
                    continue;
                }

                List<String> fields = Arrays.asList(idListStr.split(","));
                List<DTO> dtos = getCacheStore().hashMutiGet(hashKey, fields);
                if (dtos == null || dtos.isEmpty()) {
                    continue;
                }

                for (DTO dto : dtos) {
                    if (dto == null) {
                        continue;
                    }
                    try {
                        field.set(dto, newParentId);
                        ID dtoId = extractId(dto);
                        if (dtoId != null) {
                            getCacheStore().hashSet(hashKey, String.valueOf(dtoId), dto);
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }

                // 인덱스 필드 업데이트
                getCacheStore().hashDelete(hashKey, oldIndexField);
                getCacheStore().hashSetString(hashKey, getParentIndexField(parentClass, newParentId), idListStr);
            }
        }
    }

    private DTO updateDtoParentId(DTO dto, Object newParentId) {
        if (parentIdFields.isEmpty()) {
            return dto;
        }

        try {
            for (Field field : parentIdFields) {
                if (field.getType().isInstance(newParentId)) {
                    field.set(dto, newParentId);
                }
            }
            return dto;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("DTO 부모 ID 업데이트 실패", e);
        }
    }

    @SuppressWarnings("unchecked")
    private ID extractEntityId(T entity) {
        if (entity == null) {
            return null;
        }
        try {
            return (ID) entityIdField.get(entity);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("엔티티 ID 접근 실패", e);
        }
    }

    private void setEntityId(T entity, Object value) {
        if (entity == null) {
            return;
        }
        try {
            entityIdField.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("엔티티 ID 설정 실패", e);
        }
    }

    @Override
    public boolean isSyncing(Object id) {
        if (id == null)
            return false;
        // sharedsync 인프라의 SYNC_LOCK 키 사용 (RedisPresenceStorage.SYNC_LOCK 참조)
        String syncLockKey = "PRESENCE:SYNC_LOCK:" + id.toString();

        try {
            // RedisTemplate을 통해 직접 키 존재 여부 확인
            // @AutoRedisTemplate 또는 일반 redisTemplate 빈을 사용
            RedisTemplate<String, Object> redis = (RedisTemplate<String, Object>) applicationContext
                    .getBean("redisTemplate");
            return Boolean.TRUE.equals(redis.hasKey(syncLockKey));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 문자열 ID를 실제 ID 타입으로 변환
     */
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

    /**
     * 특정 리포지토리가 이 리포지토리의 부모 엔티티를 관리하는지 확인
     */
    public boolean hasParentRepository(AutoCacheRepository<?, ?, ?> parentRepo) {
        if (parentRepo == null) {
            return false;
        }
        Class<?> parentEntityClass = parentRepo.getEntityClass();
        return parentEntityClassMap.containsValue(parentEntityClass);
    }
}