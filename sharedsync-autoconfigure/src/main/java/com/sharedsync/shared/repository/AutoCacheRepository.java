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

import com.sharedsync.shared.annotation.ParentId;
import com.sharedsync.shared.annotation.TableName;
import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.history.HistoryAction;
import com.sharedsync.shared.id.IdPoolService;
import com.sharedsync.shared.repository.support.CacheEntityMetadata;
import com.sharedsync.shared.repository.support.DatabaseReader;
import com.sharedsync.shared.repository.support.DatabaseWriter;
import com.sharedsync.shared.repository.support.DeletedSetTracker;
import com.sharedsync.shared.repository.support.EntityDtoConverter;
import com.sharedsync.shared.repository.support.IdGenerator;
import com.sharedsync.shared.repository.support.IdTypeConverter;
import com.sharedsync.shared.repository.support.ParentIndex;
import com.sharedsync.shared.repository.support.ReflectionSupport;
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

    /** 리플렉션으로 1회 도출되는 불변 엔티티 메타데이터. */
    private final CacheEntityMetadata<T, ID, DTO> metadata;

    /** ID 타입 변환/임시 ID 판별 협력자. */
    private final IdTypeConverter<T, ID> idType;

    /** 삭제 추적 Set 협력자. */
    private final DeletedSetTracker<DTO> deletedSet;

    /** 부모→자식 보조 인덱스 협력자. */
    private final ParentIndex<DTO> parentIndex;

    /** DTO↔Entity 변환/병합 협력자. */
    private final EntityDtoConverter<T, ID, DTO> converter;

    /** ID 발급(IdPool/음수 임시) 협력자. */
    private final IdGenerator<T, ID, DTO> idGenerator;

    /** JPA Criteria 기반 DB 조회 협력자. */
    private final DatabaseReader<T, ID, DTO> dbReader;

    /** EntityManager 기반 DB 영속 프리미티브 협력자. */
    private final DatabaseWriter<T, ID, DTO> dbWriter;

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
        // 리플렉션 기반 메타데이터 도출은 CacheEntityMetadata 로 위임하고, 결과를 필드로 풀어 보관한다.
        this.metadata = CacheEntityMetadata.of(getClass());
        this.dtoClass = metadata.getDtoClass();
        this.cacheKeyPrefix = metadata.getCacheKeyPrefix();
        this.redisTemplateBeanName = metadata.getRedisTemplateBeanName();
        this.idField = metadata.getIdField();
        this.parentIdFields = metadata.getParentIdFields();
        this.parentEntityClassMap = metadata.getParentEntityClassMap();
        this.entityConverterMethod = metadata.getEntityConverterMethod();
        this.entityIdField = metadata.getEntityIdField();
        this.idClass = metadata.getIdClass();
        this.ignoredEntityFields = metadata.getIgnoredEntityFields();
        this.dtoFields = metadata.getDtoFields();
        this.sequenceName = metadata.getSequenceName();
        this.allocationSize = metadata.getAllocationSize();
        this.useIdPool = metadata.isUseIdPool();
        this.idType = new IdTypeConverter<>(metadata.getIdClass(), metadata.getEntityIdField(),
                metadata.isUseIdPool());
        // CacheStore 는 호출마다 재해석되도록 Supplier(this::getCacheStore)로 전달 (지연 평가).
        this.deletedSet = new DeletedSetTracker<>(metadata.getCacheKeyPrefix(), metadata.isUseIdPool(),
                this::getCacheStore);
        this.parentIndex = new ParentIndex<>(this::getCacheStore);
        // EntityManager 는 필드주입 이후 평가되도록 Supplier 로 전달.
        this.converter = new EntityDtoConverter<>(metadata, idType, () -> entityManager);
        this.idGenerator = new IdGenerator<>(metadata, this::getCacheStore,
                () -> applicationContext.getBean(IdPoolService.class), () -> entityManager);
        this.dbReader = new DatabaseReader<>(metadata, idType, () -> entityManager);
        this.dbWriter = new DatabaseWriter<>(metadata, idType, () -> entityManager);
    }

    /**
     * 스프링 빈 초기화 후 ID Pool 등록 및 초기 할당
     */
    @PostConstruct
    private void initIdPool() {
        idGenerator.init();
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
                } else if (idClass.getSimpleName().equals("BigInteger")) {
                    generatedId = java.math.BigInteger.valueOf(generateId());
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
        return deletedSet.getDeletedSetKey();
    }

    /**
     * 삭제된 영속 ID를 추적 Set에 추가합니다.
     * 임시 ID(음수)나 null은 추적하지 않습니다.
     */
    private void trackDeletedId(ID id) {
        deletedSet.trackDeletedId(id);
    }

    /**
     * 추적된 삭제 ID 목록을 반환합니다.
     */
    public Set<String> getDeletedIds() {
        return deletedSet.getDeletedIds();
    }

    /**
     * 추적된 삭제 ID 목록을 초기화합니다.
     */
    public void clearDeletedIds() {
        deletedSet.clearDeletedIds();
    }

    /**
     * DELETED Set에서 특정 ID를 제거합니다.
     * Undo/Redo로 엔티티가 복원될 때 호출하여, 동기화 시 잘못 삭제되는 것을 방지합니다.
     */
    public void removeFromDeletedSet(ID id) {
        deletedSet.removeFromDeletedSet(id);
    }

    /**
     * DELETED Set에서 특정 ID를 제거합니다 (타입 체크 없는 버전).
     */
    public void removeFromDeletedSetUnchecked(Object id) {
        deletedSet.removeFromDeletedSet(id);
    }

    private String getParentIndexField(Class<?> parentClass, Object parentId) {
        return parentIndex.getParentIndexField(parentClass, parentId);
    }

    private void addIdToParentIndex(String hashKey, Class<?> parentClass, Object parentId, ID id) {
        parentIndex.addIdToParentIndex(hashKey, parentClass, parentId, id);
    }

    private void removeIdFromParentIndex(String hashKey, Class<?> parentClass, Object parentId, ID id) {
        parentIndex.removeIdFromParentIndex(hashKey, parentClass, parentId, id);
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
            } else if (idClass.getSimpleName().equals("BigInteger")) {
                generatedId = java.math.BigInteger.valueOf(generateId());
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
        converter.mergeEntityFields(target, source);
    }

    /**
     * 기존 DTO와 새 DTO를 병합
     * 새 DTO의 null이 아닌 값들로 기존 DTO를 업데이트 (ID 제외)
     */
    private DTO mergeDto(DTO existingDto, DTO newDto) {
        return converter.mergeDto(existingDto, newDto);
    }

    private Long generateId() {
        return idGenerator.generateId();
    }

    /**
     * DTO의 ID 필드를 업데이트 (Record는 새 인스턴스 생성)
     */
    private DTO updateDtoWithId(DTO dto, ID newId) {
        return converter.updateDtoWithId(dto, newId);
    }

    protected final T convertToEntity(DTO dto) {
        return converter.convertToEntity(dto);
    }

    protected final DTO convertToDto(T entity) {
        return converter.convertToDto(entity);
    }

    @SuppressWarnings("unchecked")
    private List<T> loadEntitiesByParentId(Object parentId) {
        return dbReader.loadEntitiesByParentId(parentId);
    }

    private List<T> loadEntitiesByParentId(Object parentId, Class<?> parentClass) {
        return dbReader.loadEntitiesByParentId(parentId, parentClass);
    }

    private T loadEntityByIdCriteria(ID id) {
        return dbReader.loadEntityByIdCriteria(id);
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
        return idType.changeType(id);
    }

    private Object convertIdToType(Class<?> targetType, Object idValue) {
        return idType.convertIdToType(targetType, idValue);
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

            // 숫자 타입 비교: Long, Integer, BigInteger 등.
            // longValue() 비교는 Long 범위를 넘는 BigInteger 값을 잘라버려(2^64 modulo)
            // 서로 다른 값이 같다고 오판할 수 있으므로 BigDecimal 로 정밀 비교한다.
            if (actualValue instanceof Number && expectedValue instanceof Number) {
                return new java.math.BigDecimal(actualValue.toString())
                        .compareTo(new java.math.BigDecimal(expectedValue.toString())) == 0;
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


    /**
     * 클래스 계층에서 모든 필드 가져오기
     */
    private List<Field> getAllFieldsInHierarchy(Class<?> clazz) {
        return ReflectionSupport.getAllFieldsInHierarchy(clazz);
    }

    private String getTableName(Class<?> entityClass) {
        return ReflectionSupport.getTableName(entityClass);
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
        return ReflectionSupport.findFieldInHierarchy(clazz, fieldName);
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
        return dbWriter.insertWithOverridingSystemValue(entity);
    }

    private Object extractIdFromRelatedEntity(Object relatedEntity) {
        return dbWriter.extractIdFromRelatedEntity(relatedEntity);
    }

    private T saveEntity(T entity) {
        return dbWriter.saveEntity(entity);
    }

    private void deleteAllEntities(List<T> entities) {
        dbWriter.deleteAllEntities(entities);
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
        return idType.isPersistentId(id);
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
        return idType.isTemporaryId(id);
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

    @SuppressWarnings("unchecked")
    private ID extractEntityId(T entity) {
        return idType.extractEntityId(entity);
    }

    private void setEntityId(T entity, Object value) {
        idType.setEntityId(entity, value);
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
    public ID convertStringToId(String idStr) {
        return idType.convertStringToId(idStr);
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