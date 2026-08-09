package com.sharedsync.shared.codec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * ClientFrame 바이트를 {@link ClientFrame} 으로 디코딩한다.
 *
 * ProtoSyncCodec 의 짝이다. SyncCodec.decode 에 넣지 않은 이유: 인바운드는 "바이트 -> 지정한 타입"
 * 이 아니라 "바이트 -> oneof 어느 arm 인지 판별" 이라 시그니처가 맞지 않는다.
 */
public class ProtoFrameDecoder {

    private static final String ACTION_PREFIX = "SYNC_ACTION_";

    private final SyncDescriptors descriptors;

    public ProtoFrameDecoder(SyncDescriptors descriptors) {
        this.descriptors = descriptors;
    }

    /**
     * @throws InvalidProtocolBufferException 프레임이 스키마로 파싱되지 않을 때.
     *         스키마 스큐거나 텍스트 프레임을 바이너리로 보낸 경우다.
     */
    public ClientFrame decode(byte[] data) throws InvalidProtocolBufferException {
        Descriptor frameDesc = descriptors.message("ClientFrame");
        DynamicMessage frame = DynamicMessage.parseFrom(frameDesc, data);

        FieldDescriptor arm = setField(frame);
        if (arm == null) {
            return new ClientFrame.Unknown("빈 ClientFrame");
        }

        return switch (arm.getName()) {
            case "join" -> join((DynamicMessage) frame.getField(arm));
            case "sync" -> edit((DynamicMessage) frame.getField(arm));
            case "ping" -> new ClientFrame.Ping();
            default -> new ClientFrame.Unknown("알 수 없는 frame arm: " + arm.getName());
        };
    }

    private ClientFrame join(DynamicMessage join) {
        Descriptor desc = join.getDescriptorForType();
        return new ClientFrame.Join(
                (String) join.getField(desc.findFieldByName("room_id")),
                (String) join.getField(desc.findFieldByName("schema_hash")));
    }

    private ClientFrame edit(DynamicMessage sync) {
        Descriptor desc = sync.getDescriptorForType();

        String eventId = (String) sync.getField(desc.findFieldByName("event_id"));
        String action = actionName(sync.getField(desc.findFieldByName("action")));

        FieldDescriptor payloadArm = setField(sync, "payload");
        if (payloadArm == null) {
            // undo/redo 는 페이로드 없이 action 만 온다.
            return new ClientFrame.Edit(eventId, action, null, List.of());
        }

        DynamicMessage list = (DynamicMessage) sync.getField(payloadArm);
        // <Entity>List -> <Entity>. 생성된 디스패처의 switch 는 이 이름을 소문자로 비교한다.
        String entity = stripListSuffix(list.getDescriptorForType().getName());

        FieldDescriptor itemsField = list.getDescriptorForType().findFieldByName("items");
        int count = list.getRepeatedFieldCount(itemsField);
        List<DynamicMessage> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add((DynamicMessage) list.getRepeatedField(itemsField, i));
        }
        return new ClientFrame.Edit(eventId, action, entity, items);
    }

    /** SYNC_ACTION_UPDATE -> "update". 기존 JSON wire 의 action 문자열과 같은 값이 나온다. */
    private String actionName(Object actionValue) {
        if (!(actionValue instanceof EnumValueDescriptor evd)) {
            return null;
        }
        String name = evd.getName();
        if (name.startsWith(ACTION_PREFIX)) {
            name = name.substring(ACTION_PREFIX.length());
        }
        return "UNSPECIFIED".equals(name) ? null : name.toLowerCase();
    }

    private static String stripListSuffix(String messageName) {
        return messageName.endsWith("List")
                ? messageName.substring(0, messageName.length() - "List".length())
                : messageName;
    }

    /** 이 메시지에서 실제로 설정된 첫 필드. oneof 판별용이라 프레임 메시지에는 최대 하나만 있다. */
    private static FieldDescriptor setField(DynamicMessage message) {
        for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
            return entry.getKey();
        }
        return null;
    }

    private static FieldDescriptor setField(DynamicMessage message, String oneofName) {
        var oneof = message.getDescriptorForType().getOneofs().stream()
                .filter(o -> o.getName().equals(oneofName))
                .findFirst()
                .orElse(null);
        if (oneof == null || !message.hasOneof(oneof)) {
            return null;
        }
        return message.getOneofFieldDescriptor(oneof);
    }
}
