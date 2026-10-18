package com.sharedsync.shared.context;

/**
 * 지금 이 스레드가 어떤 루트의 캐시를 적재하는 중인지 표시한다.
 *
 * 없으면 적재 스레드가 자기가 세운 LOADING 플래그를 자기가 기다린다:
 * CacheInitializer.initializeHierarchy 가 setIsLoading(root, true) 를 켠 뒤
 * loadRecursively 로 자식을 읽는데, 그 경로의 findDtosByParentId 가 waitForLoading(root) 를
 * 부른다. 플래그를 켠 장본인이 그 플래그가 꺼지길 기다리므로 **폴링 한도(500ms × 10회)를
 * 전부 소진한 뒤에야** 진행된다 — 예외도 로그도 없이 첫 입장마다 5초가 사라졌다.
 */
public final class CacheLoadingContext {

    private static final ThreadLocal<String> LOADING_ROOT = new ThreadLocal<>();

    /**
     * 적재 중 조회가 예외를 삼켰는지. DB 조회 협력자는 예외가 나면 빈 목록이나 null 을 돌려줘서, 적재가 "성공"으로
     * 끝나도 일부가 빠진 캐시일 수 있다. 그런 캐시에는 "온전히 채워진 상태" 표시를 붙이면 안 된다.
     */
    private static final ThreadLocal<Boolean> LOAD_FAILED = new ThreadLocal<>();

    private CacheLoadingContext() {
    }

    public static void begin(String rootId) {
        LOADING_ROOT.set(rootId);
        LOAD_FAILED.remove();
    }

    public static void end() {
        LOADING_ROOT.remove();
        LOAD_FAILED.remove();
    }

    /** 적재 중인 스레드에서만 표시한다. 적재 밖의 조회 실패는 표시 대상이 아니다. */
    public static void markFailed() {
        if (LOADING_ROOT.get() != null) {
            LOAD_FAILED.set(Boolean.TRUE);
        }
    }

    public static boolean hasFailed() {
        return Boolean.TRUE.equals(LOAD_FAILED.get());
    }

    /**
     * 이 스레드가 해당 ID 의 적재 주체인지. 주체라면 기다릴 이유가 없다(자기 자신을 기다리는 꼴).
     */
    public static boolean isCurrentLoader(Object id) {
        if (id == null) {
            return false;
        }
        String loading = LOADING_ROOT.get();
        return loading != null && loading.equals(id.toString());
    }
}
