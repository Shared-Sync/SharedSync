package com.sharedsync.shared.codec;

import java.util.List;
import java.util.Map;

/**
 * 클라이언트로 나가는 메시지의 타입화된 표현.
 *
 * 예전에는 publish() 에 세 가지 서로 다른 모양이 들어왔다:
 *  - 생성 컨트롤러의 W&lt;Entity&gt;Response
 *  - HistoryService 의 Map (페이로드 키를 entityName.toLowerCase()+"s" 로 런타임에 만들었다)
 *  - PresenceBroadcaster 의 Map
 *
 * 그래서 일반 편집은 "timeTablePlaceBlockDtos", undo 는 "timetableplaceblocks" 라는 서로 다른 키로
 * 나갔고, 프론트가 `body.timeTablePlaceBlockDtos || body.timetableplaceblocks` 로 덮고 있었다.
 * 여기서 하나로 합친다 — 두 클라이언트 모두 위 폴백을 갖고 있어 기존 배포본과도 호환된다.
 */
public sealed interface SyncOutbound {

    /**
     * 엔티티 편집 브로드캐스트. undo/redo 도 같은 모양이고 isUndoRedo 로만 구분된다.
     *
     * @param dtoFieldName JSON 으로 나갈 때 쓸 리스트 필드명 (예: "timeTablePlaceBlockDtos").
     *                     기존 wire 포맷과의 호환을 위해 유지한다.
     */
    record Entities(
            String eventId,
            String action,
            String entity,
            boolean isUndoRedo,
            String dtoFieldName,
            List<?> dtos
    ) implements SyncOutbound {
    }

    /** 프레즌스 입장/퇴장 브로드캐스트. */
    record Presence(
            String action,
            String uid,
            Map<String, Object> userInfo,
            List<Map<String, Object>> users
    ) implements SyncOutbound {
    }

    /**
     * DTO 클래스명에서 기존 wire 의 리스트 필드명을 만든다.
     * 예: sharedsync.dto.TimeTablePlaceBlockDto -> timeTablePlaceBlockDtos
     */
    static String dtoFieldNameOf(String dtoClassName) {
        if (dtoClassName == null) {
            return "dtos";
        }
        String simple = dtoClassName.substring(dtoClassName.lastIndexOf('.') + 1);
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1) + "s";
    }
}
