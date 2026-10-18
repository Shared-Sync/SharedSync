package com.sharedsync.shared.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** 묶음 B: 종료 때 처리 중이던 편집 프레임이 끝나길 기다린다. */
class SyncFrameExecutorQuiescenceTest {

    @Test
    void waitsUntilQueuedAndRunningFramesFinish() throws Exception {
        SyncFrameExecutor executor = new SyncFrameExecutor(2, 100);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();
        executor.submit("s1", () -> {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.incrementAndGet();
        });
        executor.submit("s1", done::incrementAndGet);

        assertThat(executor.awaitQuiescence(100)).isFalse();

        gate.countDown();
        assertThat(executor.awaitQuiescence(2_000)).isTrue();
        assertThat(done.get()).isEqualTo(2);
        executor.shutdown();
    }

    @Test
    void discardedFramesAndFinalTaskAreAccountedFor() throws Exception {
        SyncFrameExecutor executor = new SyncFrameExecutor(1, 100);
        CountDownLatch gate = new CountDownLatch(1);
        executor.submit("s1", () -> {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        executor.submit("s1", () -> { });
        executor.submit("s1", () -> { });
        executor.terminate("s1", () -> { }); // 대기 중이던 2건은 버려진다

        gate.countDown();
        assertThat(executor.awaitQuiescence(2_000)).isTrue();
        executor.shutdown();
    }

    @Test
    void idleExecutorIsQuiescentImmediately() {
        SyncFrameExecutor executor = new SyncFrameExecutor(1, 10);
        assertThat(executor.awaitQuiescence(0)).isTrue();
        executor.shutdown();
    }
}
