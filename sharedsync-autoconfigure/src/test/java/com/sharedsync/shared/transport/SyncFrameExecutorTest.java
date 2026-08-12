package com.sharedsync.shared.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SyncFrameExecutorTest {

    private SyncFrameExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("같은 세션의 프레임은 보낸 순서대로 하나씩 실행된다")
    void framesOfOneSessionRunInOrder() throws Exception {
        executor = new SyncFrameExecutor(8, 100);
        List<Integer> order = new CopyOnWriteArrayList<>();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(50);

        for (int i = 0; i < 50; i++) {
            int index = i;
            executor.submit("session-1", () -> {
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                order.add(index);
                concurrent.decrementAndGet();
                done.countDown();
            });
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "작업이 끝나지 않았다");
        assertEquals(IntStream.range(0, 50).boxed().toList(), order,
                "create 다음에 온 update 가 뒤집혀 실행되면 아직 없는 행을 고치려 든다");
        assertEquals(1, maxConcurrent.get(), "한 세션의 프레임은 동시에 실행되면 안 된다");
    }

    @Test
    @DisplayName("서로 다른 세션은 병렬로 실행된다")
    void differentSessionsRunInParallel() throws Exception {
        executor = new SyncFrameExecutor(4, 100);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        for (int i = 0; i < 2; i++) {
            executor.submit("session-" + i, () -> {
                bothStarted.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertTrue(bothStarted.await(5, TimeUnit.SECONDS),
                "세션 간에는 막히면 안 된다 — 한 룸의 느린 편집이 다른 룸을 세우면 곤란하다");
        release.countDown();
    }

    @Test
    @DisplayName("세션 큐가 한도를 넘으면 거절한다 (조용히 버리지 않는다)")
    void rejectsWhenQueueIsFull() throws Exception {
        executor = new SyncFrameExecutor(1, 2);
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        executor.submit("session-1", () -> {
            started.countDown();
            try {
                block.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(executor.submit("session-1", () -> {}));
        assertTrue(executor.submit("session-1", () -> {}));
        assertFalse(executor.submit("session-1", () -> {}),
                "무한 큐를 두면 느린 처리 뒤에 프레임이 쌓여 힙이 먼저 죽는다");

        block.countDown();
    }

    @Test
    @DisplayName("세션이 끊기면 남은 프레임은 버리고 정리 작업만 실행한다")
    void terminateDiscardsPendingAndRunsFinalTask() throws Exception {
        executor = new SyncFrameExecutor(1, 100);
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        CountDownLatch cleaned = new CountDownLatch(1);

        executor.submit("session-1", () -> {
            started.countDown();
            try {
                block.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        executor.submit("session-1", ran::incrementAndGet);

        executor.terminate("session-1", cleaned::countDown);
        block.countDown();

        assertTrue(cleaned.await(5, TimeUnit.SECONDS), "퇴장 처리는 반드시 실행되어야 한다");
        assertEquals(0, ran.get(), "끊긴 세션의 편집을 마저 처리해도 결과를 보낼 곳이 없다");
    }

    @Test
    @DisplayName("작업이 예외를 던져도 이후 프레임 처리가 멈추지 않는다")
    void exceptionInOneFrameDoesNotStopTheQueue() throws Exception {
        executor = new SyncFrameExecutor(2, 100);
        CountDownLatch survived = new CountDownLatch(1);

        executor.submit("session-1", () -> {
            throw new IllegalStateException("의도한 실패");
        });
        executor.submit("session-1", survived::countDown);

        assertTrue(survived.await(5, TimeUnit.SECONDS),
                "예외가 새면 풀 스레드가 죽고 그 세션은 영영 멈춘다");
    }
}
