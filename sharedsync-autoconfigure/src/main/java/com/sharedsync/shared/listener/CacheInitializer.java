package com.sharedsync.shared.listener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.storage.PresenceStorage;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CacheInitializer {

    private final ApplicationContext context;

    // 전체 AutoCacheRepository 저장
    private Map<Class<?>, AutoCacheRepository<?, ?, ?>> cacheMap;

    private final PresenceStorage presenceStorage;

    @PostConstruct
    public void init() {
        cacheMap = new HashMap<>();
        Map<String, AutoCacheRepository> repositories = context.getBeansOfType(AutoCacheRepository.class);
        if (repositories.isEmpty()) {
            return;
        }

        for (AutoCacheRepository repo : repositories.values()) {
            cacheMap.put(repo.getEntityType(), repo);
        }
    }

    /**
     * 루트 엔티티 ID만 넣으면 전체 계층 캐시 자동 로딩
     */
    public void initializeHierarchy(String rootId) {
        AutoCacheRepository<?, ?, ?> rootRepo = findRootRepository();
        if (rootRepo == null) {
            return;
        }

        // 로딩 시작 마킹
        presenceStorage.setIsLoading(rootId, true);

        try {
            loadRecursively(rootRepo, rootId);
        } finally {
            // 로딩 완료 후 해제 (성공하든 실패하든)
            presenceStorage.setIsLoading(rootId, false);
        }
    }

    /**
     * 재귀적으로 캐시 로딩
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void loadRecursively(AutoCacheRepository repo, Object id) {
        // 1) DB에서 엔티티 로드
        // loadFromDatabaseById 내부에서 이미 save(dto)를 수행하여 캐시 갱신함
        CacheDto<?> dto = repo.loadFromDatabaseById(id);
        if (dto == null)
            return;

        // 2) 자식 탐색 및 재귀 로딩
        for (AutoCacheRepository childRepo : cacheMap.values()) {
            // 자기 자신 제외
            if (childRepo == repo)
                continue;

            // 해당 리포지토리가 이 리포지토리의 자식인지 확인 (즉, childRepo의 부모가 repo인지)
            // childRepo.isParentEntityOf(repo.getEntityType()) -> childRepo가 repo의 부모인지
            // 확인하는 것임 (반대)
            // childRepo가 repo의 자식이어야 함. 즉, childRepo.parentEntityClassMap에 repoType이 있어야 함.
            if (!childRepo.hasParentRepository(repo)) {
                continue;
            }

            // 3) 자식 DTO 목록 로드 (DB -> Cache)
            // loadFromDatabaseByParentIdUnchecked 내부에서 saveAll을 수행함
            List<? extends CacheDto<?>> children = childRepo.loadFromDatabaseByParentIdUnchecked(id);

            // 4) 각 자식에 대해 재귀 호출 (손자 로딩)
            if (children != null && !children.isEmpty()) {
                for (CacheDto<?> childDto : children) {
                    Object childId = childRepo.extractIdUnchecked(childDto);
                    if (childId != null) {
                        loadRecursively(childRepo, childId);
                    }
                }
            }
        }
    }

    /**
     * ParentId 없는 엔티티 = 루트
     */
    private AutoCacheRepository<?, ?, ?> findRootRepository() {
        return cacheMap.values().stream()
                .filter(repo -> !repo.isParentIdFieldPresent())
                .findFirst()
                .orElse(null);
    }
}
