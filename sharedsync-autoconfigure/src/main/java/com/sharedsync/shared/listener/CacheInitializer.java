package com.sharedsync.shared.listener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.context.CacheLoadingContext;
import com.sharedsync.shared.storage.PresenceStorage;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

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
     * 루트 엔티티 ID만 넣으면 전체 계층 캐시 자동 로딩.
     *
     * <p>끝까지 성공하면 "온전히 채워진 상태" 표시({@link PresenceStorage#markCacheReady})를 남긴다. 재입장 때
     * 이 표시와 루트 캐시가 있으면 DB에서 다시 채우지 않는다({@code PresenceSessionManager}). 그래도 되는 전제는
     * <b>남아 있는 캐시가 항상 DB와 같거나 더 새롭다</b>는 것이다: DB에 쓰는 경로는 캐시→DB 동기화와, 캐시도 함께
     * 고치는 경로뿐이어야 한다. 캐시를 거치지 않고 일정 데이터를 DB에 쓰는 경로가 생기면 이 전제가 깨진다.
     * (예외: 루트가 DB에서 사라지는 경우 — 주인 없는 캐시 처리가 그 캐시를 지운다.)</p>
     */
    public void initializeHierarchy(String rootId) {
        AutoCacheRepository<?, ?, ?> rootRepo = findRootRepository();
        if (rootRepo == null) {
            return;
        }

        // 로딩 시작 마킹
        presenceStorage.setIsLoading(rootId, true);
        // 적재 주체를 표시한다. 이게 없으면 아래 loadRecursively 안의 findDtosByParentId 가
        // 방금 자기가 켠 LOADING 플래그를 기다리며 5초를 통째로 버린다.
        CacheLoadingContext.begin(rootId);

        try {
            loadRecursively(rootRepo, rootId);
            // DB 조회가 예외를 삼키면 빈 목록·null 이 돌아와 일부가 빠진 채 "성공"한다. 그런 캐시에는 표시를 붙이지 않는다.
            if (!CacheLoadingContext.hasFailed() && hasCachedRoot(rootRepo, rootId)) {
                presenceStorage.markCacheReady(rootId);
            }
        } finally {
            // 로딩 완료 후 해제 (성공하든 실패하든)
            CacheLoadingContext.end();
            presenceStorage.setIsLoading(rootId, false);
        }
    }

    /** 루트 DTO가 캐시에 있는지. */
    public boolean hasCachedRoot(String rootId) {
        AutoCacheRepository<?, ?, ?> rootRepo = findRootRepository();
        return rootRepo != null && hasCachedRoot(rootRepo, rootId);
    }

    private static boolean hasCachedRoot(AutoCacheRepository<?, ?, ?> rootRepo, String rootId) {
        Object id = rootRepo.convertStringToId(rootId);
        return id != null && rootRepo.findDtoByIdUnchecked(id) != null;
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
