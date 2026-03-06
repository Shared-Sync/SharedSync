package com.sharedsync.shared.repository;

import java.util.List;
import java.util.Set;

/**
 * 캐시 저장소 추상화 인터페이스
 * Redis 또는 InMemory 구현체를 교체할 수 있습니다.
 *
 * @param <V> 값 타입 (DTO)
 */
public interface CacheStore<V> {

    /**
     * 키로 값 조회
     */
    V get(String key);

    /**
     * 키-값 저장
     */
    void set(String key, V value);

    /**
     * 키 삭제
     */
    void delete(String key);

    /**
     * 키 존재 여부 확인
     */
    boolean hasKey(String key);

    /**
     * 여러 키로 값 조회
     */
    List<V> multiGet(List<String> keys);

    /**
     * 패턴에 맞는 모든 키 조회 (예: "plan:*")
     */
    Set<String> keys(String pattern);

    /**
     * 카운터 감소 (원자적) - 임시 ID 생성용
     */
    Long decrement(String key);

    /**
     * Set에 값 추가
     */
    void addToSet(String key, String value);

    /**
     * Set에서 값 제거
     */
    void removeFromSet(String key, String value);

    /**
     * Set의 모든 값 조회
     */
    Set<String> getSet(String key);

    /**
     * Hash에 값 저장
     */
    void hashSet(String key, String field, V value);

    /**
     * Hash에서 값 조회
     */
    V hashGet(String key, String field);

    /**
     * Hash에서 여러 필드 조회
     */
    List<V> hashMutiGet(String key, List<String> fields);

    /**
     * Hash에서 필드 삭제
     */
    void hashDelete(String key, String field);

    /**
     * Hash의 모든 필드명 조회
     */
    Set<String> hashkeys(String key);

    /**
     * Hash에 문자열 값 저장 (인덱스용)
     */
    void hashSetString(String key, String field, String value);

    /**
     * Hash에서 문자열 값 조회 (인덱스용)
     */
    String hashGetString(String key, String field);
}
