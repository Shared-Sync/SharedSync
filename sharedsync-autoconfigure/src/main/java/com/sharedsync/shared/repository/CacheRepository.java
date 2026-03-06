package com.sharedsync.shared.repository;

import java.util.List;
import java.util.Optional;

/**
 * 공통 캐시 인터페이스 - JpaRepository와 유사한 방식으로 캐시 작업을 수행
 * 
 * @param <T>   엔티티 타입
 * @param <ID>  ID 타입
 * @param <DTO> DTO 타입
 */
public interface CacheRepository<T, ID, DTO> {

    // ==== 기본 CRUD 작업 ====

    /**
     * ID로 엔티티 조회
     */
    Optional<T> findById(ID id);

    /**
     * ID로 엔티티 조회 (필수 - 없으면 예외)
     */
    T getReferenceById(ID id);

    /**
     * 엔티티 저장/수정
     */
    DTO save(DTO dto);

    /**
     * 엔티티 삭제
     */
    void deleteById(ID id);

    /**
     * 엔티티 존재 여부 확인
     */
    boolean existsById(ID id);

    // ==== 배치 작업 ====

    /**
     * 여러 ID로 조회
     */
    List<T> findAllById(Iterable<ID> ids);

    /**
     * 여러 엔티티 저장
     */
    List<DTO> saveAll(List<DTO> dtos);

    /**
     * 여러 ID로 삭제
     */
    void deleteAllById(Iterable<ID> ids);

    // ==== 관계 기반 조회 (각 캐시별로 필요한 메서드 추가) ====

    /**
     * 상위 엔티티로 하위 엔티티들 조회
     */
    default List<T> findByParentId(Object parentId) {
        return findByParentId(parentId, null);
    }

    default List<T> findByParentId(java.util.UUID parentId) {
        return findByParentId((Object) parentId, null);
    }

    default List<T> findByParentId(Integer parentId) {
        return findByParentId((Object) parentId, null);
    }

    default List<T> findByParentId(Object parentId, Class<?> parentClass) {
        throw new UnsupportedOperationException("findByParentId not implemented");
    }

    /**
     * 상위 엔티티로 하위 엔티티들 삭제
     */
    default List<T> deleteByParentId(Object parentId) {
        return deleteByParentId(parentId, null);
    }

    default List<T> deleteByParentId(Object parentId, Class<?> parentClass) {
        throw new UnsupportedOperationException("deleteByParentId not implemented");
    }

    // ==== DB 동기화 작업 ====

    /**
     * DB에서 데이터를 로드하여 캐시에 저장
     */
    default List<DTO> loadFromDatabaseByParentId(Object parentId) {
        return loadFromDatabaseByParentId(parentId, null);
    }

    default List<DTO> loadFromDatabaseByParentId(Object parentId, Class<?> parentClass) {
        throw new UnsupportedOperationException("loadFromDatabase not implemented");
    }

    /**
     * 캐시 데이터 초기화
     */
    default void clear() {
        throw new UnsupportedOperationException("clear not implemented");
    }

    /**
     * 현재 동기화(DB Flush) 진행 중인지 확인
     */
    default boolean isSyncing(Object id) {
        return false;
    }

    /**
     * 현재 캐시 로딩(Initialization) 진행 중인지 확인
     */
    default boolean isLoading(Object id) {
        return false;
    }
}