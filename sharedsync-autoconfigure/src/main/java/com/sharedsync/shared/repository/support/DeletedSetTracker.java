package com.sharedsync.shared.repository.support;

import java.util.Set;
import java.util.function.Supplier;

import com.sharedsync.shared.repository.CacheStore;

/**
 * 캐시에서 삭제된 영속 ID를 추적하는 Redis Set(키: {@code <prefix>:DELETED})을 관리하는 협력자.
 *
 * <p>동기화 시 캐시에서 사라진 엔티티를 DB에서도 안정적으로 삭제하기 위한 추적 정보를 보관한다.
 * 임시(음수) ID는 DB에 존재하지 않으므로 추적하지 않는다.</p>
 *
 * <p>{@link CacheStore} 는 호출마다 재해석되도록 {@link Supplier} 로 주입받는다(빈 교체/폴백 동작 보존).</p>
 *
 * @param <V> 캐시 값(DTO) 타입
 */
public final class DeletedSetTracker<V> {

    private final String cacheKeyPrefix;
    private final boolean useIdPool;
    private final Supplier<CacheStore<V>> store;

    public DeletedSetTracker(String cacheKeyPrefix, boolean useIdPool, Supplier<CacheStore<V>> store) {
        this.cacheKeyPrefix = cacheKeyPrefix;
        this.useIdPool = useIdPool;
        this.store = store;
    }

    /** 삭제 추적용 Redis Set 키 ({@code <prefix>:DELETED}). */
    public String getDeletedSetKey() {
        return cacheKeyPrefix + ":DELETED";
    }

    /** 삭제된 영속 ID를 추적 Set에 추가한다. 임시 ID(음수)나 null은 추적하지 않는다. */
    public void trackDeletedId(Object id) {
        if (id == null)
            return;
        // 임시(음수) ID는 DB에 없으므로 추적 불필요
        if (!useIdPool && id instanceof Number number && number.longValue() < 0L) {
            return;
        }
        store.get().addToSet(getDeletedSetKey(), String.valueOf(id));
    }

    /** 추적된 삭제 ID 목록을 반환한다. */
    public Set<String> getDeletedIds() {
        return store.get().getSet(getDeletedSetKey());
    }

    /** 추적된 삭제 ID 목록을 초기화한다. */
    public void clearDeletedIds() {
        store.get().delete(getDeletedSetKey());
    }

    /** DELETED Set에서 특정 ID를 제거한다(Undo/Redo 복원 시 잘못된 삭제 방지). */
    public void removeFromDeletedSet(Object id) {
        if (id == null)
            return;
        store.get().removeFromSet(getDeletedSetKey(), String.valueOf(id));
    }
}
