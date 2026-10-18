package com.sharedsync.shared.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import com.sharedsync.shared.context.CacheLoadingContext;
import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.storage.InMemoryPresenceStorage;

/** 묶음 B: "온전히 채워진 상태" 표시는 적재가 끝까지 성공했을 때만 붙는다(U3, 11-1). */
class CacheInitializerReadyTest {

    private static final String ROOT = "1";

    private final InMemoryPresenceStorage storage = new InMemoryPresenceStorage();
    @SuppressWarnings("rawtypes")
    private final AutoCacheRepository rootRepo = mock(AutoCacheRepository.class);
    private final CacheDto<?> rootDto = mock(CacheDto.class);

    @SuppressWarnings("unchecked")
    private CacheInitializer initializer() {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(AutoCacheRepository.class)).thenReturn(Map.of("root", rootRepo));
        when(rootRepo.getEntityType()).thenReturn((Class) Object.class);
        when(rootRepo.isParentIdFieldPresent()).thenReturn(false);
        when(rootRepo.convertStringToId(ROOT)).thenReturn(1L);
        CacheInitializer initializer = new CacheInitializer(context, storage);
        initializer.init();
        return initializer;
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulLoadMarksReady() {
        CacheInitializer initializer = initializer();
        when(rootRepo.loadFromDatabaseById(any())).thenReturn(rootDto);
        when(rootRepo.findDtoByIdUnchecked(1L)).thenReturn(rootDto);

        initializer.initializeHierarchy(ROOT);

        assertThat(storage.isCacheReady(ROOT)).isTrue();
        assertThat(storage.isLoading(ROOT)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void U3_swallowedQueryFailureDoesNotMarkReady() {
        CacheInitializer initializer = initializer();
        when(rootRepo.loadFromDatabaseById(any())).thenAnswer(inv -> {
            CacheLoadingContext.markFailed(); // DB 조회 협력자가 예외를 삼켰을 때와 같다
            return rootDto;
        });
        when(rootRepo.findDtoByIdUnchecked(1L)).thenReturn(rootDto);

        initializer.initializeHierarchy(ROOT);

        assertThat(storage.isCacheReady(ROOT)).isFalse();
        assertThat(CacheLoadingContext.hasFailed()).isFalse(); // 다음 적재로 번지지 않는다
    }

    @Test
    void missingRootDoesNotMarkReady() {
        CacheInitializer initializer = initializer();
        when(rootRepo.loadFromDatabaseById(any())).thenReturn(null);

        initializer.initializeHierarchy(ROOT);

        assertThat(storage.isCacheReady(ROOT)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadThatThrowsDoesNotMarkReady() {
        CacheInitializer initializer = initializer();
        when(rootRepo.loadFromDatabaseById(any())).thenThrow(new IllegalStateException("boom"));

        try {
            initializer.initializeHierarchy(ROOT);
        } catch (IllegalStateException expected) {
            // 예전과 같이 호출자에게 전파된다
        }

        assertThat(storage.isCacheReady(ROOT)).isFalse();
        assertThat(storage.isLoading(ROOT)).isFalse();
    }
}
