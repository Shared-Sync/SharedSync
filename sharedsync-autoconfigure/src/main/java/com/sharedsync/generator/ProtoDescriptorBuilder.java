package com.sharedsync.generator;

import java.util.List;
import java.util.Map;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.sharedsync.generator.Generator.CacheInformation;

/**
 * ProtoSchemaGenerator 가 만드는 .proto 텍스트와 **같은 필드 모델**에서 FileDescriptorProto 를 조립한다.
 *
 * 둘이 같은 WireFieldResolver 결과를 쓰므로 구조적으로 어긋날 수 없고, 그럼에도 어긋나면
 * Backend-v2 의 컨포먼스 테스트가 buf 로 만든 디스크립터와 비교해 잡아낸다.
 *
 * protoc 없이 손으로 조립하는 것이라 proto3 optional 표현에 주의가 필요하다:
 * proto3 의 명시적 presence 는 필드에 proto3_optional=true 를 세우고 필드마다 "_<name>" 이라는
 * synthetic oneof 를 만들어 oneof_index 로 가리키는 형태다. protoc 가 산출하는 형태와 맞추기 위해
 * 여기서도 동일하게 만든다.
 */
public final class ProtoDescriptorBuilder {

    private ProtoDescriptorBuilder() {
    }

    public static FileDescriptorProto build(List<CacheInformation> cacheInfoList,
                                             String protoPackage,
                                             String protoFile,
                                             ProtoTypeMapper mapper) {
        FileDescriptorProto.Builder file = FileDescriptorProto.newBuilder()
                .setName(protoFile)
                .setSyntax("proto3")
                .setPackage(protoPackage);

        file.addEnumType(syncActionEnum());

        // 엔티티 메시지를 먼저 만들어 mapper 가 enum 을 수집하게 한다.
        DescriptorProto[] entityMessages = new DescriptorProto[cacheInfoList.size()];
        DescriptorProto[] listMessages = new DescriptorProto[cacheInfoList.size()];
        for (int i = 0; i < cacheInfoList.size(); i++) {
            CacheInformation info = cacheInfoList.get(i);
            entityMessages[i] = entityMessage(info, protoPackage, mapper);
            listMessages[i] = listMessage(info.getEntityName(), protoPackage);
        }

        for (Map.Entry<String, String> entry : mapper.getEnumTypes().entrySet()) {
            file.addEnumType(javaEnum(entry.getValue(), mapper.enumConstants(entry.getKey())));
        }
        for (int i = 0; i < entityMessages.length; i++) {
            file.addMessageType(entityMessages[i]);
            file.addMessageType(listMessages[i]);
        }

        addEnvelopes(file, cacheInfoList, protoPackage);
        return file.build();
    }

    // ==========================================
    // enums
    // ==========================================
    private static EnumDescriptorProto syncActionEnum() {
        return EnumDescriptorProto.newBuilder()
                .setName("SyncAction")
                .addValue(enumValue("SYNC_ACTION_UNSPECIFIED", 0))
                .addValue(enumValue("SYNC_ACTION_CREATE", 1))
                .addValue(enumValue("SYNC_ACTION_UPDATE", 2))
                .addValue(enumValue("SYNC_ACTION_DELETE", 3))
                .addValue(enumValue("SYNC_ACTION_UNDO", 4))
                .addValue(enumValue("SYNC_ACTION_REDO", 5))
                .build();
    }

    private static EnumDescriptorProto javaEnum(String protoEnumName, List<String> constants) {
        String prefix = ProtoTypeMapper.enumValuePrefix(protoEnumName);
        EnumDescriptorProto.Builder builder = EnumDescriptorProto.newBuilder()
                .setName(protoEnumName)
                .addValue(enumValue(prefix + "_UNSPECIFIED", 0));
        int number = 1;
        for (String constant : constants) {
            builder.addValue(enumValue(prefix + "_" + constant, number++));
        }
        return builder.build();
    }

    private static EnumValueDescriptorProto enumValue(String name, int number) {
        return EnumValueDescriptorProto.newBuilder().setName(name).setNumber(number).build();
    }

    // ==========================================
    // messages
    // ==========================================
    private static DescriptorProto entityMessage(CacheInformation info, String protoPackage, ProtoTypeMapper mapper) {
        DescriptorProto.Builder message = DescriptorProto.newBuilder().setName(info.getEntityName());

        List<WireField> fields = WireFieldResolver.resolve(info);
        int number = 1;
        int syntheticOneofIndex = 0;

        for (WireField field : fields) {
            String protoType = field.isRepeated()
                    ? mapper.toProtoType(field.getElementType())
                    : mapper.toProtoType(field.getJavaType());

            FieldDescriptorProto.Builder fd = FieldDescriptorProto.newBuilder()
                    .setName(field.getProtoName())
                    .setNumber(number++);

            applyType(fd, protoType, protoPackage);

            if (field.isRepeated()) {
                fd.setLabel(FieldDescriptorProto.Label.LABEL_REPEATED);
            } else {
                fd.setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
                if (!field.isJavaPrimitive()) {
                    // 명시적 presence: proto3_optional + synthetic oneof
                    fd.setProto3Optional(true);
                    fd.setOneofIndex(syntheticOneofIndex++);
                    message.addOneofDecl(OneofDescriptorProto.newBuilder()
                            .setName("_" + field.getProtoName()));
                }
            }
            message.addField(fd);
        }
        return message.build();
    }

    private static DescriptorProto listMessage(String entityName, String protoPackage) {
        return DescriptorProto.newBuilder()
                .setName(entityName + "List")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("items")
                        .setNumber(1)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("." + protoPackage + "." + entityName))
                .build();
    }

    private static void applyType(FieldDescriptorProto.Builder fd, String protoType, String protoPackage) {
        switch (protoType) {
            case "string" -> fd.setType(FieldDescriptorProto.Type.TYPE_STRING);
            case "int64" -> fd.setType(FieldDescriptorProto.Type.TYPE_INT64);
            case "int32" -> fd.setType(FieldDescriptorProto.Type.TYPE_INT32);
            case "bool" -> fd.setType(FieldDescriptorProto.Type.TYPE_BOOL);
            case "double" -> fd.setType(FieldDescriptorProto.Type.TYPE_DOUBLE);
            case "float" -> fd.setType(FieldDescriptorProto.Type.TYPE_FLOAT);
            default -> {
                // 스키마 안에서 정의한 enum
                fd.setType(FieldDescriptorProto.Type.TYPE_ENUM);
                fd.setTypeName("." + protoPackage + "." + protoType);
            }
        }
    }

    // ==========================================
    // envelopes
    // ==========================================
    private static void addEnvelopes(FileDescriptorProto.Builder file, List<CacheInformation> cacheInfoList,
                                      String pkg) {
        file.addMessageType(DescriptorProto.newBuilder()
                .setName("Join")
                .addField(stringField("room_id", 1))
                .addField(stringField("schema_hash", 2)));

        file.addMessageType(DescriptorProto.newBuilder().setName("Ping"));
        file.addMessageType(DescriptorProto.newBuilder().setName("Pong"));

        file.addMessageType(DescriptorProto.newBuilder()
                .setName("Error")
                .addField(stringField("code", 1))
                .addField(stringField("message", 2)));

        file.addMessageType(DescriptorProto.newBuilder()
                .setName("Hello")
                .addField(stringField("schema_hash", 1)));

        file.addMessageType(presenceUserMessage(pkg));
        file.addMessageType(presenceEventMessage(pkg));

        file.addMessageType(syncRequestMessage(cacheInfoList, pkg));
        file.addMessageType(syncEventMessage(cacheInfoList, pkg));
        file.addMessageType(clientFrameMessage(pkg));
        file.addMessageType(serverFrameMessage(pkg));
    }

    private static DescriptorProto presenceUserMessage(String pkg) {
        return DescriptorProto.newBuilder()
                .setName("PresenceUser")
                .addField(stringField("uid", 1))
                .addField(mapField("user_info", 2, pkg, "PresenceUser"))
                .addNestedType(mapEntry("UserInfoEntry"))
                .build();
    }

    private static DescriptorProto presenceEventMessage(String pkg) {
        return DescriptorProto.newBuilder()
                .setName("PresenceEvent")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("action").setNumber(1)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName("." + pkg + ".SyncAction"))
                .addField(stringField("uid", 2))
                .addField(mapField("user_info", 3, pkg, "PresenceEvent"))
                .addNestedType(mapEntry("UserInfoEntry"))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("users").setNumber(4)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("." + pkg + ".PresenceUser"))
                .build();
    }

    /** map<string,string> 은 repeated <Name>Entry 메시지로 표현된다. */
    private static FieldDescriptorProto mapField(String name, int number, String pkg, String parentMessage) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName("." + pkg + "." + parentMessage + ".UserInfoEntry")
                .build();
    }

    private static DescriptorProto mapEntry(String name) {
        return DescriptorProto.newBuilder()
                .setName(name)
                .addField(stringField("key", 1))
                .addField(stringField("value", 2))
                .setOptions(com.google.protobuf.DescriptorProtos.MessageOptions.newBuilder()
                        .setMapEntry(true))
                .build();
    }

    private static DescriptorProto syncRequestMessage(List<CacheInformation> cacheInfoList, String pkg) {
        DescriptorProto.Builder b = DescriptorProto.newBuilder()
                .setName("SyncRequest")
                .addField(stringField("event_id", 1))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("action").setNumber(2)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName("." + pkg + ".SyncAction"))
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("payload"));
        addPayloadArms(b, cacheInfoList, pkg, 0);
        return b.build();
    }

    private static DescriptorProto syncEventMessage(List<CacheInformation> cacheInfoList, String pkg) {
        DescriptorProto.Builder b = DescriptorProto.newBuilder()
                .setName("SyncEvent")
                .addField(stringField("event_id", 1))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("action").setNumber(2)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName("." + pkg + ".SyncAction"))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("is_undo_redo").setNumber(3)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setType(FieldDescriptorProto.Type.TYPE_BOOL))
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("payload"));
        addPayloadArms(b, cacheInfoList, pkg, 0);
        return b.build();
    }

    private static void addPayloadArms(DescriptorProto.Builder b, List<CacheInformation> cacheInfoList,
                                        String pkg, int oneofIndex) {
        int number = 10;
        for (CacheInformation info : cacheInfoList) {
            String entity = info.getEntityName();
            b.addField(FieldDescriptorProto.newBuilder()
                    .setName(WireField.toSnakeCase(entity) + "s")
                    .setNumber(number++)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName("." + pkg + "." + entity + "List")
                    .setOneofIndex(oneofIndex));
        }
    }

    private static DescriptorProto clientFrameMessage(String pkg) {
        return DescriptorProto.newBuilder()
                .setName("ClientFrame")
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("frame"))
                .addField(oneofMessageField("join", 1, pkg, "Join"))
                .addField(oneofMessageField("sync", 2, pkg, "SyncRequest"))
                .addField(oneofMessageField("ping", 3, pkg, "Ping"))
                .build();
    }

    private static DescriptorProto serverFrameMessage(String pkg) {
        return DescriptorProto.newBuilder()
                .setName("ServerFrame")
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("frame"))
                .addField(oneofMessageField("hello", 1, pkg, "Hello"))
                .addField(oneofMessageField("sync", 2, pkg, "SyncEvent"))
                .addField(oneofMessageField("presence", 3, pkg, "PresenceEvent"))
                .addField(oneofMessageField("pong", 4, pkg, "Pong"))
                .addField(oneofMessageField("error", 5, pkg, "Error"))
                .build();
    }

    private static FieldDescriptorProto oneofMessageField(String name, int number, String pkg, String type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName("." + pkg + "." + type)
                .setOneofIndex(0)
                .build();
    }

    private static FieldDescriptorProto stringField(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .build();
    }
}
