package com.sharedsync.shared.transport;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 프레임 처리를 컨테이너 스레드 밖으로 빼되, 세션별 순서는 유지하는 실행기.
 *
 * raw WebSocket 은 핸들러가 소켓의 읽기 스레드에서 그대로 돌아간다. 편집 처리는 DB·Redis 왕복이라
 * 거기서 하면 그 소켓의 읽기 루프가 통째로 막힌다. STOMP 에서는 clientInboundChannel 의 스레드풀이
 * 이 분리를 해줬고, 그래서 transport 를 바꾸면서 조용히 사라진 것이 이 경계다.
 *
 * 그렇다고 일반 스레드풀에 그냥 던지면 안 된다. 같은 세션의 create 와 뒤이은 update 가 서로 다른
 * 스레드에서 뒤집혀 실행되면 update 가 아직 없는 행을 고치려 든다. 세션마다 큐를 두고 한 번에 하나씩만
 * 흘려보내 순서를 지킨다 (세션 간에는 병렬).
 */
@Slf4j
public class SyncFrameExecutor {

    private final ExecutorService pool;
    private final int queueLimit;
    private final Map<String, SessionQueue> queues = new ConcurrentHashMap<>();

    public SyncFrameExecutor(int threads, int queueLimit) {
        this.queueLimit = queueLimit;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "sharedsync-frame-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.pool = Executors.newFixedThreadPool(threads, factory);
        log.info("[SharedSync] 프레임 실행기 시작: threads={}, session-queue-limit={}", threads, queueLimit);
    }

    /**
     * 세션 순서를 지켜 실행한다.
     *
     * @return 큐가 한도를 넘어 거절되면 false. 호출부가 백프레셔를 클라이언트에 알려야 한다 —
     *         조용히 버리면 클라이언트는 편집이 반영된 줄 안다.
     */
    public boolean submit(String sessionId, Runnable task) {
        SessionQueue queue = queues.computeIfAbsent(sessionId, id -> new SessionQueue());
        if (!queue.offer(task)) {
            log.warn("[SharedSync] 세션 큐 초과로 프레임 거절 sessionId={} limit={}", sessionId, queueLimit);
            return false;
        }
        drainIfIdle(sessionId, queue);
        return true;
    }

    /**
     * 세션 종료. 남은 작업은 버리고 마지막 작업(정리)만 실행한다.
     * 끊긴 세션의 편집을 마저 처리해봐야 결과를 보낼 곳이 없다.
     */
    public void terminate(String sessionId, Runnable finalTask) {
        SessionQueue queue = queues.remove(sessionId);
        int discarded = queue == null ? 0 : queue.discardPending();
        if (discarded > 0) {
            log.debug("[SharedSync] 종료된 세션의 대기 프레임 {}건 폐기 sessionId={}", discarded, sessionId);
        }
        pool.execute(guard(finalTask, sessionId));
    }

    private void drainIfIdle(String sessionId, SessionQueue queue) {
        if (!queue.markRunningIfIdle()) {
            return;
        }
        pool.execute(() -> drain(sessionId, queue));
    }

    private void drain(String sessionId, SessionQueue queue) {
        while (true) {
            Runnable task = queue.poll();
            if (task == null) {
                queue.markIdle();
                // markIdle 과 offer 사이의 경합: 그 사이에 들어온 작업이 있으면 다시 집어든다.
                if (queue.isEmpty() || !queue.markRunningIfIdle()) {
                    return;
                }
                continue;
            }
            guard(task, sessionId).run();
        }
    }

    private Runnable guard(Runnable task, String sessionId) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                // 여기서 새면 풀 스레드가 죽는다. 세션 하나의 실패가 전체에 번지면 안 된다.
                log.error("[SharedSync] 프레임 처리 중 예외 sessionId={}: {}", sessionId, e.getMessage(), e);
            }
        };
    }

    /** 관측용. 실제 대기 중인 프레임 총합. */
    public int pendingFrames() {
        return queues.values().stream().mapToInt(SessionQueue::size).sum();
    }

    public int activeSessions() {
        return queues.size();
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 세션 하나의 직렬 큐. running 플래그로 동시에 한 스레드만 흘려보낸다. */
    private final class SessionQueue {

        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private boolean running;

        synchronized boolean offer(Runnable task) {
            if (tasks.size() >= queueLimit) {
                return false;
            }
            tasks.addLast(task);
            return true;
        }

        synchronized Runnable poll() {
            return tasks.pollFirst();
        }

        synchronized boolean isEmpty() {
            return tasks.isEmpty();
        }

        synchronized int size() {
            return tasks.size();
        }

        synchronized boolean markRunningIfIdle() {
            if (running) {
                return false;
            }
            running = true;
            return true;
        }

        synchronized void markIdle() {
            running = false;
        }

        synchronized int discardPending() {
            int size = tasks.size();
            tasks.clear();
            return size;
        }
    }
}
