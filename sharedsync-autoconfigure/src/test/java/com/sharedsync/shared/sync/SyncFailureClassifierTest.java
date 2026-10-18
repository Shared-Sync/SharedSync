package com.sharedsync.shared.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;

import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.UnexpectedRollbackException;

class SyncFailureClassifierTest {

    @Test
    void connectionFailuresAreRecognizedAnywhereInTheCauseChain() {
        assertThat(SyncFailureClassifier.isConnectionFailure(new CannotCreateTransactionException("x"))).isTrue();
        assertThat(SyncFailureClassifier.isConnectionFailure(
                new RuntimeException(new JDBCConnectionException("x", new java.sql.SQLException())))).isTrue();
        assertThat(SyncFailureClassifier.isConnectionFailure(
                new RuntimeException(new RuntimeException(new SQLTransientConnectionException("x"))))).isTrue();
    }

    @Test
    void dataFailuresAreNotConnectionFailures() {
        assertThat(SyncFailureClassifier.isConnectionFailure(new DataIntegrityViolationException("uk"))).isFalse();
        // 유니크 위반 뒤 커밋에서 나는 예외(버그 12에서 실제로 난 모양)
        assertThat(SyncFailureClassifier.isConnectionFailure(new UnexpectedRollbackException("rollback-only")))
                .isFalse();
        assertThat(SyncFailureClassifier.isConnectionFailure(null)).isFalse();
    }
}
