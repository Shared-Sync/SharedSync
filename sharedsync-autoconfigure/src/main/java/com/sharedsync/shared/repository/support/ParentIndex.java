package com.sharedsync.shared.repository.support;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

import com.sharedsync.shared.repository.CacheStore;

/**
 * 부모 ID → 자식 ID 목록 보조 인덱스(Redis 해시 필드 {@code P_IDX:<ParentEntity>:<parentId>})를 관리하는 협력자.
 *
 * <p>{@code findByParentId} 류 조회가 Repository 메서드 없이도 동작하도록, 부모별 자식 ID를
 * 콤마 구분 문자열로 캐시 해시에 유지한다. 인덱스 갱신은 인스턴스 단위로 동기화한다
 * (리포지토리당 하나의 ParentIndex → 기존 {@code synchronized(this)} 와 동일한 상호배제 범위).</p>
 *
 * @param <V> 캐시 값(DTO) 타입
 */
public final class ParentIndex<V> {

    private final Supplier<CacheStore<V>> store;

    public ParentIndex(Supplier<CacheStore<V>> store) {
        this.store = store;
    }

    /** 부모 인덱스 해시 필드 이름을 만든다. */
    public String getParentIndexField(Class<?> parentClass, Object parentId) {
        return "P_IDX:" + parentClass.getSimpleName() + ":" + parentId;
    }

    /** 부모 인덱스에 자식 ID를 추가한다. */
    public void addIdToParentIndex(String hashKey, Class<?> parentClass, Object parentId, Object id) {
        if (parentId == null || parentClass == null)
            return;
        String field = getParentIndexField(parentClass, parentId);
        String idStr = String.valueOf(id);

        synchronized (this) {
            String existing = store.get().hashGetString(hashKey, field);
            if (existing == null || existing.isEmpty()) {
                store.get().hashSetString(hashKey, field, idStr);
            } else {
                Set<String> ids = new LinkedHashSet<>(Arrays.asList(existing.split(",")));
                if (ids.add(idStr)) {
                    store.get().hashSetString(hashKey, field, String.join(",", ids));
                }
            }
        }
    }

    /** 부모 인덱스에서 자식 ID를 제거한다(비면 필드 자체 삭제). */
    public void removeIdFromParentIndex(String hashKey, Class<?> parentClass, Object parentId, Object id) {
        if (parentId == null || parentClass == null)
            return;
        String field = getParentIndexField(parentClass, parentId);
        String idStr = String.valueOf(id);

        synchronized (this) {
            String existing = store.get().hashGetString(hashKey, field);
            if (existing != null && !existing.isEmpty()) {
                Set<String> ids = new LinkedHashSet<>(Arrays.asList(existing.split(",")));
                if (ids.remove(idStr)) {
                    if (ids.isEmpty()) {
                        store.get().hashDelete(hashKey, field);
                    } else {
                        store.get().hashSetString(hashKey, field, String.join(",", ids));
                    }
                }
            }
        }
    }
}
