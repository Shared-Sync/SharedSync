package com.sharedsync.shared.codec;

import java.util.Base64;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;

/**
 * 생성된 sharedsync.proto.SyncSchema 에서 디스크립터를 읽어 들고 있는다.
 *
 * SyncSchema 는 소비 애플리케이션의 컴파일 시점에 생성되므로 SharedSync 자체에는 존재하지 않는다.
 * 그래서 컴파일 의존이 아니라 리플렉션으로 찾는다 — 없으면 protobuf 코덱을 쓸 수 없다는 뜻이라
 * 기동 시 명확히 실패시킨다.
 */
public final class SyncDescriptors {

    private static final String SCHEMA_CLASS = "sharedsync.proto.SyncSchema";

    private final FileDescriptor fileDescriptor;
    private final String schemaHash;
    private final String protoText;

    public SyncDescriptors() {
        try {
            Class<?> schema = Class.forName(SCHEMA_CLASS);
            String base64 = (String) schema.getField("DESCRIPTOR_BASE64").get(null);
            this.schemaHash = (String) schema.getField("SCHEMA_HASH").get(null);
            this.protoText = (String) schema.getField("PROTO_TEXT").get(null);

            FileDescriptorProto proto = FileDescriptorProto.parseFrom(Base64.getDecoder().decode(base64));
            this.fileDescriptor = FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "protobuf 코덱을 쓰려면 " + SCHEMA_CLASS + " 가 필요하다. "
                            + "@CacheEntity 가 있는 애플리케이션에서 sharedsync-autoconfigure 를 "
                            + "annotationProcessor 로 걸었는지 확인할 것.", e);
        } catch (Exception e) {
            throw new IllegalStateException("wire 스키마 디스크립터 로드 실패", e);
        }
    }

    public FileDescriptor getFileDescriptor() {
        return fileDescriptor;
    }

    public String getSchemaHash() {
        return schemaHash;
    }

    public String getProtoText() {
        return protoText;
    }

    public Descriptor message(String name) {
        Descriptor descriptor = fileDescriptor.findMessageTypeByName(name);
        if (descriptor == null) {
            throw new IllegalArgumentException("wire 스키마에 없는 메시지: " + name);
        }
        return descriptor;
    }
}
