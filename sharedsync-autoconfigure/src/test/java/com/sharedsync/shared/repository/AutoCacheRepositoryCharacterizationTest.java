package com.sharedsync.shared.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import com.sharedsync.shared.annotation.Cache;
import com.sharedsync.shared.annotation.CacheId;
import com.sharedsync.shared.annotation.EntityConverter;
import com.sharedsync.shared.annotation.ParentId;
import com.sharedsync.shared.dto.CacheDto;

import jakarta.persistence.Id;

/**
 * AutoCacheRepository 의 핵심 동작을 "있는 그대로" 고정하는 특성화(characterization) 테스트.
 *
 * <p>리팩토링(메타데이터/ID 타입변환/삭제셋/부모인덱스 협력자 추출) 중에 동작이 바뀌면
 * 즉시 깨지도록, 캐시 키 포맷·ID 변환 규칙·임시 ID 판별·부모 인덱스 갱신을 명시적으로 검증한다.
 * 캐시 전용 경로만 다루므로 {@link CacheStore} 는 Mockito 목으로 대체한다.</p>
 */
@ExtendWith(MockitoExtension.class)
public class AutoCacheRepositoryCharacterizationTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    @SuppressWarnings("rawtypes")
    private CacheStore cacheStore;

    @SuppressWarnings("unchecked")
    private <R extends AutoCacheRepository<?, ?, ?>> R inject(R repository) throws Exception {
        lenient().when(applicationContext.containsBean(anyString())).thenReturn(false);
        lenient().when(applicationContext.containsBean("globalCacheStore")).thenReturn(true);
        lenient().when(applicationContext.getBean("globalCacheStore")).thenReturn(cacheStore);

        java.lang.reflect.Field field = AutoCacheRepository.class.getDeclaredField("applicationContext");
        field.setAccessible(true);
        field.set(repository, applicationContext);
        return repository;
    }

    // ===== 캐시 키 포맷 (메타데이터/keyspace) =====

    @Test
    void redisKey_and_deletedSetKey_useCacheKeyPrefix() throws Exception {
        UuidRepository repo = inject(new UuidRepository());

        // getRedisKey 는 id 값과 무관하게 항상 "<prefix>:DATA" (단일 해시 레이아웃)
        assertThat(repo.getRedisKey(UUID.randomUUID())).isEqualTo("uu:DATA");
        assertThat(repo.getDeletedSetKey()).isEqualTo("uu:DELETED");
    }

    @Test
    void cacheKeyPrefix_isDerivedFromDtoClassName_whenKeyTypeEmpty() throws Exception {
        // @Cache(keyType="") → "PlainDto" 에서 "Dto" 제거 후 소문자 = "plain"
        PlainRepository repo = inject(new PlainRepository());
        assertThat(repo.getDeletedSetKey()).isEqualTo("plain:DELETED");
    }

    @Test
    void deletedSet_helpers_useDeletedSetKey() throws Exception {
        UuidRepository repo = inject(new UuidRepository());

        repo.clearDeletedIds();
        verify(cacheStore).delete("uu:DELETED");

        repo.getDeletedIds();
        verify(cacheStore).getSet("uu:DELETED");
    }

    // ===== ID 타입 변환 (IdTypeConverter) =====

    @Test
    void convertStringToId_uuid() throws Exception {
        UuidRepository repo = inject(new UuidRepository());
        UUID id = UUID.randomUUID();
        assertThat(repo.convertStringToId(id.toString())).isEqualTo(id);
    }

    @Test
    void convertStringToId_long() throws Exception {
        LongRepository repo = inject(new LongRepository());
        assertThat(repo.convertStringToId("42")).isEqualTo(42L);
    }

    @Test
    void convertStringToId_integer() throws Exception {
        IntRepository repo = inject(new IntRepository());
        assertThat(repo.convertStringToId("7")).isEqualTo(7);
    }

    @Test
    void convertStringToId_invalidValue_throws() throws Exception {
        LongRepository repo = inject(new LongRepository());
        assertThatThrownBy(() -> repo.convertStringToId("not-a-number"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertStringToId_bigInteger_beyondLongRange() throws Exception {
        BigIntRepository repo = inject(new BigIntRepository());
        String huge = "18446744073709551617"; // 2^64 + 1, > Long.MAX_VALUE
        assertThat(repo.convertStringToId(huge)).isEqualTo(new java.math.BigInteger(huge));
    }

    // ===== 숫자 필드 비교 (matchesFieldValue) — BigInteger 절단 회귀 방지 =====

    @Test
    void matchesFieldValue_bigInteger_beyondLongRange_doesNotTruncate() throws Exception {
        BigIntRepository repo = inject(new BigIntRepository());
        BigIntDto dto = new BigIntDto();
        dto.id = new java.math.BigInteger("18446744073709551617"); // 2^64 + 1

        java.lang.reflect.Field idField = BigIntDto.class.getDeclaredField("id");
        idField.setAccessible(true);
        java.lang.reflect.Method matches = AutoCacheRepository.class.getDeclaredMethod(
                "matchesFieldValue", CacheDto.class, java.lang.reflect.Field.class, Object.class);
        matches.setAccessible(true);

        // 절단되면 2^64+1 → 1 이 되어 Long 1 과 같다고 오판한다. 정밀 비교는 false 여야 한다.
        assertThat((boolean) matches.invoke(repo, dto, idField, 1L)).isFalse();
        // 동일한 큰 값은 정상적으로 일치
        assertThat((boolean) matches.invoke(repo, dto, idField,
                new java.math.BigInteger("18446744073709551617"))).isTrue();
        // 교차 타입(Long ↔ BigInteger)의 동일 값 비교도 여전히 동작
        BigIntDto small = new BigIntDto();
        small.id = java.math.BigInteger.valueOf(5L);
        assertThat((boolean) matches.invoke(repo, small, idField, 5L)).isTrue();
    }

    // ===== 임시 ID 판별 (IdTypeConverter) =====

    @Test
    void isPersistentId_negativeNumericId_isTemporary_forNonPoolEntity() throws Exception {
        LongRepository repo = inject(new LongRepository());

        assertThat(repo.isPersistentId(5L)).isTrue();    // 양수 = 영속
        assertThat(repo.isPersistentId(-1L)).isFalse();  // 음수 = 임시(레거시 모드)
        assertThat(repo.isPersistentId(null)).isFalse(); // null = 영속 아님
    }

    @Test
    void isPersistentId_uuidId_isAlwaysPersistent() throws Exception {
        UuidRepository repo = inject(new UuidRepository());
        assertThat(repo.isPersistentId(UUID.randomUUID())).isTrue();
    }

    // ===== save 동작 (CRUD / IdGenerator / DeletedSetTracker) =====

    @Test
    void save_assignsUuid_whenIdIsNull() throws Exception {
        UuidRepository repo = inject(new UuidRepository());

        UuidDto dto = new UuidDto(); // id == null
        UuidDto saved = repo.save(dto);

        assertThat(saved.getId()).isNotNull();
        verify(cacheStore).hashSet(eq("uu:DATA"), anyString(), any());
        // 저장은 항상 DELETED set 에서 해당 id 를 제거(복원 안전성)
        verify(cacheStore).removeFromSet(eq("uu:DELETED"), eq(saved.getId().toString()));
    }

    // ===== 부모 인덱스 (ParentIndex) =====

    @Test
    void save_writesParentIndexEntry_whenIndexEmpty() throws Exception {
        ChildRepository repo = inject(new ChildRepository());

        UUID childId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        ChildDto dto = new ChildDto();
        dto.id = childId;
        dto.parentId = parentId;

        // 부모 인덱스가 비어있다고 가정 → hashSetString 으로 신규 기록
        lenient().when(cacheStore.hashGetString(anyString(), anyString())).thenReturn(null);

        repo.save(dto);

        verify(cacheStore).hashSetString(
                eq("child:DATA"),
                eq("P_IDX:ParentEntity:" + parentId),
                eq(childId.toString()));
    }

    // ===== 테스트용 엔티티/DTO/리포지토리 =====

    public static class UuidEntity {
        @Id
        private UUID id;
    }

    @Cache(keyType = "uu")
    public static class UuidDto extends CacheDto<UUID> {
        @CacheId
        private UUID id;

        @Override
        public UUID getId() {
            return id;
        }

        @EntityConverter
        public UuidEntity toEntity() {
            return new UuidEntity();
        }
    }

    public static class UuidRepository extends AutoCacheRepository<UuidEntity, UUID, UuidDto> {
    }

    public static class LongEntity {
        @Id
        private Long id;
    }

    @Cache(keyType = "lng")
    public static class LongDto extends CacheDto<Long> {
        @CacheId
        private Long id;

        @Override
        public Long getId() {
            return id;
        }

        @EntityConverter
        public LongEntity toEntity() {
            return new LongEntity();
        }
    }

    public static class LongRepository extends AutoCacheRepository<LongEntity, Long, LongDto> {
    }

    public static class BigIntEntity {
        @Id
        private java.math.BigInteger id;
    }

    @Cache(keyType = "big")
    public static class BigIntDto extends CacheDto<java.math.BigInteger> {
        @CacheId
        private java.math.BigInteger id;

        @Override
        public java.math.BigInteger getId() {
            return id;
        }

        @EntityConverter
        public BigIntEntity toEntity() {
            return new BigIntEntity();
        }
    }

    public static class BigIntRepository extends AutoCacheRepository<BigIntEntity, java.math.BigInteger, BigIntDto> {
    }

    public static class IntEntity {
        @Id
        private Integer id;
    }

    @Cache(keyType = "it")
    public static class IntDto extends CacheDto<Integer> {
        @CacheId
        private Integer id;

        @Override
        public Integer getId() {
            return id;
        }

        @EntityConverter
        public IntEntity toEntity() {
            return new IntEntity();
        }
    }

    public static class IntRepository extends AutoCacheRepository<IntEntity, Integer, IntDto> {
    }

    /** keyType 미지정 → 클래스 이름에서 prefix 유도 검증용 */
    public static class PlainEntity {
        @Id
        private UUID id;
    }

    @Cache
    public static class PlainDto extends CacheDto<UUID> {
        @CacheId
        private UUID id;

        @Override
        public UUID getId() {
            return id;
        }

        @EntityConverter
        public PlainEntity toEntity() {
            return new PlainEntity();
        }
    }

    public static class PlainRepository extends AutoCacheRepository<PlainEntity, UUID, PlainDto> {
    }

    /** 부모 엔티티 마커 (인덱스 필드 이름 유도에만 사용) */
    public static class ParentEntity {
    }

    public static class ChildEntity {
        @Id
        private UUID id;
    }

    @Cache(keyType = "child")
    public static class ChildDto extends CacheDto<UUID> {
        @CacheId
        UUID id;

        @ParentId(ParentEntity.class)
        UUID parentId;

        @Override
        public UUID getId() {
            return id;
        }

        @EntityConverter
        public ChildEntity toEntity() {
            return new ChildEntity();
        }
    }

    public static class ChildRepository extends AutoCacheRepository<ChildEntity, UUID, ChildDto> {
    }
}
