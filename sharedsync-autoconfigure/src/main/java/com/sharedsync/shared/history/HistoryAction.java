package com.sharedsync.shared.history;

import java.util.List;

import com.sharedsync.shared.dto.CacheDto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoryAction {
    public enum Type {
        CREATE, UPDATE, DELETE,
        /**
         * 실체 DTO 변경이 없는 순수 컨테이너. 서로 무관한 여러 연산(create/update/delete, 여러
         * 엔티티 타입 혼합)을 undo 1회에 묶어 되돌려야 하는 소비자(예: 챗봇 [반영] reconcile)를 위한 것.
         * applyInverse/applyAction/publishChange 는 COMPOSITE 를 만나면 자신의 before/after 는
         * 건드리지 않고 subActions 만 그대로 재귀한다 — subActions 각각은 기존처럼 독립적으로
         * 성공/실패한다(하나가 상태불일치로 no-op 이어도 나머지는 정상 적용).
         */
        COMPOSITE
    }

    private Type type;
    private String entityName;
    private String dtoClassName;
    private List<? extends CacheDto<?>> beforeData;
    private List<? extends CacheDto<?>> afterData;
    private List<HistoryAction> subActions; // 자식 엔티티들에 대한 히스토리
    private String eventId;
    private long timestamp;
}
