package com.sharedsync.shared.id;

import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

/**
 * 개별 엔티티 타입의 ID Pool 설정 및 리필 동기화를 관리하는 클래스.
 * 실제 ID 저장소는 Redis Set (IDPOOL:{sequenceName})이며,
 * 이 클래스는 설정과 리필 플래그만 보유합니다.
 */
@Slf4j
public class IdPool {

    private final String sequenceName;
    private final int allocationSize;
    private final AtomicBoolean refilling = new AtomicBoolean(false);

    /**
     * Pool이 이 임계값 이하로 떨어지면 비동기 리필을 트리거합니다.
     */
    private static final double REFILL_THRESHOLD_RATIO = 0.2;

    public IdPool(String sequenceName, int allocationSize) {
        this.sequenceName = sequenceName;
        this.allocationSize = allocationSize;
    }

    public String getSequenceName() {
        return sequenceName;
    }

    public int getAllocationSize() {
        return allocationSize;
    }

    /**
     * 주어진 현재 Pool 크기가 리필 임계값 이하인지 확인합니다.
     */
    public boolean needsRefill(long currentSize) {
        return currentSize <= (int) (allocationSize * REFILL_THRESHOLD_RATIO);
    }

    /**
     * 리필 플래그를 CAS로 설정합니다 (중복 리필 방지).
     * 
     * @return true면 리필 시작 가능, false면 이미 리필 중
     */
    public boolean tryStartRefill() {
        return refilling.compareAndSet(false, true);
    }

    /**
     * 리필 완료 후 플래그를 해제합니다.
     */
    public void finishRefill() {
        refilling.set(false);
    }
}
