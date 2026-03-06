package com.sharedsync.shared.repository;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import com.sharedsync.shared.annotation.Cache;
import com.sharedsync.shared.annotation.CacheId;
import com.sharedsync.shared.dto.CacheDto;

import jakarta.persistence.Id;

@ExtendWith(MockitoExtension.class)
public class AutoCacheRepositoryResilienceTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private CacheStore<TestDto> cacheStore;

    private TestRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        // Mock ApplicationContext behavior for getCacheStore()
        lenient().when(applicationContext.containsBean(anyString())).thenReturn(false);
        lenient().when(applicationContext.containsBean("globalCacheStore")).thenReturn(true);
        lenient().when(applicationContext.getBean("globalCacheStore")).thenReturn(cacheStore);

        repository = new TestRepository();

        // Inject applicationContext manually
        java.lang.reflect.Field field = AutoCacheRepository.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        field.set(repository, applicationContext);
    }

    @Test
    void save_ShouldRemoveFromDeletedSet() {
        TestDto dto = new TestDto();
        UUID id = UUID.randomUUID();
        dto.setId(id);

        repository.save(dto);

        // Verify that removeFromSet was called for the DELETED set
        verify(cacheStore).removeFromSet(anyString(), eq(id.toString()));
    }

    @Test
    void saveAll_ShouldRemoveFromDeletedSet() {
        TestDto dto = new TestDto();
        UUID id = UUID.randomUUID();
        dto.setId(id);

        repository.saveAll(Collections.singletonList(dto));

        // Verify that removeFromSet was called for the DELETED set
        verify(cacheStore).removeFromSet(anyString(), eq(id.toString()));
    }

    @Test
    void update_ShouldRemoveFromDeletedSet() {
        TestDto dto = new TestDto();
        UUID id = UUID.randomUUID();
        dto.setId(id);

        // Mock existing DTO for update
        lenient().when(cacheStore.hashGet(anyString(), eq(id.toString()))).thenReturn(dto);

        repository.update(dto);

        // Verify that removeFromSet was called for the DELETED set
        verify(cacheStore).removeFromSet(anyString(), eq(id.toString()));
    }

    @Test
    void deleteById_ShouldTrackDeletedId() {
        UUID id = UUID.randomUUID();

        repository.deleteById(id);

        // Verify that addToSet was called for the DELETED set (tracking)
        verify(cacheStore).addToSet(eq("test:DELETED"), eq(id.toString()));
    }

    @Test
    void deleteCacheOnlyById_ShouldNotTrackDeletedId() {
        UUID id = UUID.randomUUID();

        repository.deleteCacheOnlyById(id);

        // Verify that addToSet was NOT called (no tracking)
        verify(cacheStore, never()).addToSet(eq("test:DELETED"), anyString());
    }

    // --- Mock Classes for Test ---

    public static class TestEntity {
        @Id
        private UUID id;
    }

    @Cache(keyType = "test")
    public static class TestDto extends CacheDto<UUID> {
        @CacheId
        private UUID id;

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public void setId(UUID id) {
            this.id = id;
        }

        public TestEntity toEntity() {
            return new TestEntity();
        }
    }

    // Generic reflection will handle class extraction
    public static class TestRepository extends AutoCacheRepository<TestEntity, UUID, TestDto> {
    }
}
