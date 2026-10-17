package com.sharedsync.shared.repository.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.sharedsync.shared.repository.CacheStore;

/**
 * 캐시에서 삭제된 영속 ID를 추적해, 동기화가 DB에서도 지우게 하는 협력자.
 *
 * <p>추적 저장소는 두 가지다.</p>
 * <ul>
 *   <li><b>루트별 해시</b> {@code <prefix>:DELETED_BY_ROOT} — 필드는 삭제된 ID, 값은 그 엔티티의 루트 ID.
 *       각 루트의 동기화는 자기 루트 것만 처리하고, 커밋이 성공한 뒤 처리한 ID만 지운다. 예전 전역 Set 은
 *       어느 루트의 동기화든 전부 처리하고 커밋 전에 통째로 지워서, 실패하는 루트 하나가 다른 루트의 삭제를
 *       가져가 롤백시키면 그 삭제가 영영 사라졌다.</li>
 *   <li><b>전역 Set</b> {@code <prefix>:DELETED} (예전 방식) — 루트를 알 수 없을 때와 루트 엔티티 자신의 삭제는
 *       예전처럼 여기에 쌓는다. 배포 순간 남아 있던 항목도 여기 있다. 동기화가 DB 기준으로 루트를 해석해
 *       자기 것만 처리한다.</li>
 * </ul>
 *
 * <p>임시(음수) ID는 DB에 없으므로 추적하지 않는다. {@link CacheStore} 는 호출마다 재해석되도록
 * {@link Supplier} 로 주입받는다.</p>
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

    /** 예전 전역 삭제 추적 Set 키 ({@code <prefix>:DELETED}). */
    public String getDeletedSetKey() {
        return cacheKeyPrefix + ":DELETED";
    }

    /** 루트별 삭제 추적 해시 키 ({@code <prefix>:DELETED_BY_ROOT}). */
    public String getDeletedByRootKey() {
        return cacheKeyPrefix + ":DELETED_BY_ROOT";
    }

    private boolean isTrackable(Object id) {
        if (id == null)
            return false;
        // 임시(음수) ID는 DB에 없으므로 추적 불필요
        return useIdPool || !(id instanceof Number number && number.longValue() < 0L);
    }

    /** 루트를 모를 때·루트 엔티티 자신일 때의 예전 방식 추적. */
    public void trackDeletedId(Object id) {
        if (!isTrackable(id))
            return;
        store.get().addToSet(getDeletedSetKey(), String.valueOf(id));
    }

    /** 삭제된 ID를 그 루트와 함께 추적한다. */
    public void track(Object id, Object rootId) {
        if (!isTrackable(id))
            return;
        if (rootId == null) {
            trackDeletedId(id);
            return;
        }
        store.get().hashSetString(getDeletedByRootKey(), String.valueOf(id), String.valueOf(rootId));
    }

    /** 이 루트 소속으로 추적된 삭제 ID 목록. */
    public List<String> pendingFor(Object rootId) {
        String root = String.valueOf(rootId);
        CacheStore<V> s = store.get();
        Set<String> ids = s.hashkeys(getDeletedByRootKey());
        List<String> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (String id : ids) {
            if (root.equals(s.hashGetString(getDeletedByRootKey(), id))) {
                result.add(id);
            }
        }
        return result;
    }

    /** 커밋 후 정리: 처리한 루트별 추적과 예전 Set 항목만 지운다. 그 사이 새로 들어온 추적은 남는다. */
    public void clear(Collection<String> byRootIds, Collection<String> legacyIds) {
        CacheStore<V> s = store.get();
        for (String id : byRootIds) {
            s.hashDelete(getDeletedByRootKey(), id);
        }
        for (String id : legacyIds) {
            s.removeFromSet(getDeletedSetKey(), id);
        }
    }

    /** 예전 전역 Set 의 추적 ID 목록. */
    public Set<String> getDeletedIds() {
        return store.get().getSet(getDeletedSetKey());
    }

    /**
     * 예전 전역 Set 을 통째로 지운다.
     *
     * @deprecated 커밋 전에 다른 루트의 추적까지 지우던 원인이다. {@link #clear(Collection, Collection)} 를 쓴다.
     */
    @Deprecated
    public void clearDeletedIds() {
        store.get().delete(getDeletedSetKey());
    }

    /** 추적 해제(Undo/Redo 복원·재저장 시 잘못된 삭제 방지). 두 저장소 모두에서 뺀다. */
    public void removeFromDeletedSet(Object id) {
        if (id == null)
            return;
        String key = String.valueOf(id);
        store.get().removeFromSet(getDeletedSetKey(), key);
        store.get().hashDelete(getDeletedByRootKey(), key);
    }

    /** 테스트·진단용: 루트별 해시에 남은 추적의 루트 값. */
    public String rootOf(Object id) {
        return Objects.toString(store.get().hashGetString(getDeletedByRootKey(), String.valueOf(id)), null);
    }
}
