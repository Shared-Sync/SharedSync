package com.sharedsync.shared.transport;

/**
 * 서버가 Error 프레임에 실어 보내는 코드.
 *
 * 클라이언트가 분기하는 값이므로 계약이다. 문자열을 호출부에 흩뿌려두면 오타 하나가
 * "클라이언트가 처리하지 못하는 에러"로 조용히 바뀌고, 메트릭 태그도 함께 갈라진다.
 *
 * 각 코드에 대해 클라이언트가 무엇을 해야 하는지가 이 목록의 핵심이다 — 재시도할 수 있는 것과
 * 재연결해도 소용없는 것이 섞여 있다.
 */
public final class SyncErrorCode {

    /** 프레임이 스키마로 파싱되지 않는다. 재전송은 무의미하다 — 스키마를 다시 받아야 한다. */
    public static final String MALFORMED_FRAME = "MALFORMED_FRAME";

    /** 이 서버가 모르는 oneof arm. 클라이언트가 더 새 버전이라는 뜻이다. */
    public static final String UNKNOWN_FRAME = "UNKNOWN_FRAME";

    /** room_id 가 비어 있다. */
    public static final String INVALID_JOIN = "INVALID_JOIN";

    /**
     * 클라이언트 스키마 해시가 서버와 다르다. 서버가 연결을 끊는다.
     * 클라이언트는 스키마를 다시 받아 코드를 재생성해야 한다 — 재연결만 반복하면 계속 끊긴다.
     */
    public static final String SCHEMA_MISMATCH = "SCHEMA_MISMATCH";

    /** 핸드셰이크에서 사용자를 확인하지 못했다. 토큰을 갱신해 다시 연결할 것. */
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

    /** 인증은 됐지만 이 룸에 접근할 수 없다. 재시도는 무의미하다. */
    public static final String ACCESS_DENIED = "ACCESS_DENIED";

    /** Join 없이 편집을 보냈다. Join 후 재전송하면 된다. */
    public static final String NOT_JOINED = "NOT_JOINED";

    /**
     * 처리 대기열이 가득 찼다. 이 프레임은 처리되지 않았으므로 클라이언트가 다시 보내야 한다 —
     * 조용히 버리면 편집이 반영된 줄 안다.
     */
    public static final String BACKPRESSURE = "BACKPRESSURE";

    /** 편집 처리 중 서비스 계층에서 실패했다. 같은 요청을 다시 보내면 같은 결과일 가능성이 높다. */
    public static final String EDIT_FAILED = "EDIT_FAILED";

    /** 그 밖의 서버 오류. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private SyncErrorCode() {
    }
}
