package com.sharedsync.shared.codec;

import java.util.List;
import java.util.Map;

import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;

/**
 * protobuf wire 코덱.
 *
 * SyncOutbound(타입화된 엔벨로프)를 ServerFrame 바이트로 만든다. content-type 을
 * application/octet-stream 으로 두는 것이 중요하다 — StompSubProtocolHandler 는 이 값일 때만
 * BinaryMessage 로 내보내고, 아니면 바이트를 UTF-8 로 디코딩해 조용히 손상시킨다.
 */
public class ProtoSyncCodec implements SyncCodec {

    private final SyncDescriptors descriptors;

    public ProtoSyncCodec(SyncDescriptors descriptors) {
        this.descriptors = descriptors;
    }

    public SyncDescriptors getDescriptors() {
        return descriptors;
    }

    @Override
    public MimeType contentType() {
        return MimeTypeUtils.APPLICATION_OCTET_STREAM;
    }

    @Override
    public byte[] encode(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        if (payload instanceof byte[] raw) {
            return raw;
        }
        if (payload instanceof SyncOutbound.Entities entities) {
            return serverFrame("sync", syncEvent(entities)).toByteArray();
        }
        if (payload instanceof SyncOutbound.Presence presence) {
            return serverFrame("presence", presenceEvent(presence)).toByteArray();
        }
        throw new IllegalArgumentException(
                "protobuf 코덱이 처리할 수 없는 페이로드: " + payload.getClass().getName()
                        + " — SyncOutbound 로 감싸서 보낼 것");
    }

    /** 스키마 해시를 실어 보내는 최초 프레임. 클라이언트가 자기 해시와 비교한다. */
    public byte[] encodeHello() {
        Descriptor helloDesc = descriptors.message("Hello");
        DynamicMessage hello = DynamicMessage.newBuilder(helloDesc)
                .setField(helloDesc.findFieldByName("schema_hash"), descriptors.getSchemaHash())
                .build();
        return serverFrame("hello", hello).toByteArray();
    }

    public byte[] encodeError(String code, String message) {
        Descriptor errorDesc = descriptors.message("Error");
        DynamicMessage error = DynamicMessage.newBuilder(errorDesc)
                .setField(errorDesc.findFieldByName("code"), code)
                .setField(errorDesc.findFieldByName("message"), message == null ? "" : message)
                .build();
        return serverFrame("error", error).toByteArray();
    }

    public byte[] encodePong() {
        return serverFrame("pong", DynamicMessage.newBuilder(descriptors.message("Pong")).build())
                .toByteArray();
    }

    // ==========================================
    // 내부 조립
    // ==========================================
    private DynamicMessage serverFrame(String armName, DynamicMessage value) {
        Descriptor frameDesc = descriptors.message("ServerFrame");
        return DynamicMessage.newBuilder(frameDesc)
                .setField(frameDesc.findFieldByName(armName), value)
                .build();
    }

    private DynamicMessage syncEvent(SyncOutbound.Entities entities) {
        Descriptor eventDesc = descriptors.message("SyncEvent");
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(eventDesc);

        builder.setField(eventDesc.findFieldByName("event_id"),
                entities.eventId() == null ? "" : entities.eventId());
        builder.setField(eventDesc.findFieldByName("action"), actionValue(eventDesc, entities.action()));
        builder.setField(eventDesc.findFieldByName("is_undo_redo"), entities.isUndoRedo());

        List<?> dtos = entities.dtos();
        if (dtos != null && !dtos.isEmpty()) {
            // 엔티티 이름은 DTO 클래스에서 얻는다 — entity 문자열은 대소문자가 제각각이다.
            String entityName = entityNameOf(dtos.get(0).getClass());
            FieldDescriptor arm = eventDesc.findFieldByName(WireNames.toSnakeCase(entityName) + "s");
            if (arm == null) {
                throw new IllegalStateException("wire 스키마에 없는 엔티티 oneof arm: " + entityName);
            }

            Descriptor listDesc = descriptors.message(entityName + "List");
            Descriptor itemDesc = descriptors.message(entityName);
            DynamicMessage.Builder listBuilder = DynamicMessage.newBuilder(listDesc);
            FieldDescriptor itemsField = listDesc.findFieldByName("items");
            for (Object dto : dtos) {
                listBuilder.addRepeatedField(itemsField, DtoProtoMapper.toMessage(dto, itemDesc));
            }
            builder.setField(arm, listBuilder.build());
        }
        return builder.build();
    }

    private DynamicMessage presenceEvent(SyncOutbound.Presence presence) {
        Descriptor eventDesc = descriptors.message("PresenceEvent");
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(eventDesc);

        builder.setField(eventDesc.findFieldByName("action"), actionValue(eventDesc, presence.action()));
        builder.setField(eventDesc.findFieldByName("uid"), presence.uid() == null ? "" : presence.uid());
        putStringMap(builder, eventDesc.findFieldByName("user_info"), presence.userInfo());

        if (presence.users() != null) {
            Descriptor userDesc = descriptors.message("PresenceUser");
            FieldDescriptor usersField = eventDesc.findFieldByName("users");
            for (Map<String, Object> user : presence.users()) {
                DynamicMessage.Builder userBuilder = DynamicMessage.newBuilder(userDesc);
                Object uid = user.get("uid");
                userBuilder.setField(userDesc.findFieldByName("uid"), uid == null ? "" : uid.toString());

                @SuppressWarnings("unchecked")
                Map<String, Object> info = (Map<String, Object>) user.get("userInfo");
                putStringMap(userBuilder, userDesc.findFieldByName("user_info"), info);
                builder.addRepeatedField(usersField, userBuilder.build());
            }
        }
        return builder.build();
    }

    /** map&lt;string,string&gt; 은 repeated Entry 메시지다. 값은 toString 으로 평탄화한다. */
    private void putStringMap(DynamicMessage.Builder builder, FieldDescriptor mapField,
                              Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Descriptor entryDesc = mapField.getMessageType();
        FieldDescriptor keyField = entryDesc.findFieldByName("key");
        FieldDescriptor valueField = entryDesc.findFieldByName("value");

        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            builder.addRepeatedField(mapField, DynamicMessage.newBuilder(entryDesc)
                    .setField(keyField, entry.getKey())
                    .setField(valueField, entry.getValue().toString())
                    .build());
        }
    }

    private Object actionValue(Descriptor owner, String action) {
        FieldDescriptor fd = owner.findFieldByName("action");
        String name = "SYNC_ACTION_" + (action == null ? "UNSPECIFIED" : action.toUpperCase());
        var value = fd.getEnumType().findValueByName(name);
        return value != null ? value : fd.getEnumType().findValueByNumber(0);
    }

    /** sharedsync.dto.TimeTablePlaceBlockDto -> TimeTablePlaceBlock */
    private static String entityNameOf(Class<?> dtoClass) {
        String simple = dtoClass.getSimpleName();
        return simple.endsWith("Dto") ? simple.substring(0, simple.length() - 3) : simple;
    }

    static final class WireNames {
        static String toSnakeCase(String camel) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < camel.length(); i++) {
                char c = camel.charAt(i);
                if (Character.isUpperCase(c)) {
                    if (i > 0) sb.append('_');
                    sb.append(Character.toLowerCase(c));
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
