package com.sharedsync.shared.sync;

/**
 * 동기화 실패가 "DB에 닿지 못한 것"인지 가린다.
 *
 * <p>연속 실패 횟수는 그 방이 막혔는지(데이터 때문에 매번 실패하는지) 판단하는 데 쓴다. DB 연결이 끊긴 동안의
 * 실패까지 세면, 장애 동안 나간 방이 모두 막힌 방으로 취급되어 재입장 때 남은 캐시를 버리고 DB에서 다시 채운다.
 * 그래서 연결 계열 실패는 세지 않는다.</p>
 *
 * <p>판별은 아래 세 예외로만 한다. 분류를 넓힐수록 그 자체가 오판의 원인이 된다.</p>
 */
public final class SyncFailureClassifier {

    private static final int MAX_CAUSE_DEPTH = 16;

    private SyncFailureClassifier() {
    }

    public static boolean isConnectionFailure(Throwable error) {
        Throwable current = error;
        for (int i = 0; current != null && i < MAX_CAUSE_DEPTH; i++) {
            if (current instanceof org.springframework.transaction.CannotCreateTransactionException
                    || current instanceof org.hibernate.exception.JDBCConnectionException
                    || current instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }
}
