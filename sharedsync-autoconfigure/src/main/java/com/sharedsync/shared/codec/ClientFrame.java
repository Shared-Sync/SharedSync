package com.sharedsync.shared.codec;

import java.util.List;

import com.google.protobuf.DynamicMessage;

/**
 * 클라이언트 -> 서버 프레임의 디코딩 결과.
 *
 * wire 의 ClientFrame(oneof join|sync|ping)을 transport 가 다루기 좋은 모양으로 옮긴 것이다.
 * 여기서 DynamicMessage 를 그대로 들고 있는 이유: 엔티티별 DTO 클래스는 소비 애플리케이션의
 * 생성물이라 프레임워크가 컴파일 시점에 알 수 없다. 생성된 디스패처가 자기 DTO 타입으로
 * {@link DtoProtoMapper#toDto} 를 호출해 마지막에 변환한다.
 */
public sealed interface ClientFrame {

    /** 룸 입장. 이 프레임을 받기 전까지 세션은 어느 룸에도 속하지 않는다. */
    record Join(String roomId, String schemaHash) implements ClientFrame {
    }

    /**
     * 편집 요청. undo/redo 는 payload 없이 action 만 실려 오므로 entity 가 null 이고 items 가 비어 있다.
     *
     * @param action 소문자 동작명("create"/"update"/"delete"/"undo"/"redo"). 기존 JSON wire 와 같은 값이라
     *               디스패처의 분기 로직을 두 transport 가 공유한다.
     */
    record Edit(String eventId, String action, String entity, List<DynamicMessage> items) implements ClientFrame {
    }

    /** 애플리케이션 레벨 하트비트. */
    record Ping() implements ClientFrame {
    }

    /**
     * oneof 가 비어 있거나 이 서버가 모르는 arm 인 경우.
     *
     * 파싱 실패와 구분한다 — 전자는 클라이언트가 신버전이라는 뜻이고, 후자는 프레임이 깨진 것이다.
     */
    record Unknown(String detail) implements ClientFrame {
    }
}
