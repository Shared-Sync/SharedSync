package com.sharedsync.shared.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;

class DtoProtoMapperTest {

    /** 스키마 생성기가 만드는 것과 같은 모양: 참조 타입은 optional, 컬렉션은 repeated. */
    private static Descriptor sampleDescriptor() throws Exception {
        DescriptorProto message = DescriptorProto.newBuilder()
                .setName("Sample")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("owner_id")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setProto3Optional(true)
                        .setOneofIndex(0))
                .addOneofDecl(com.google.protobuf.DescriptorProtos.OneofDescriptorProto.newBuilder()
                        .setName("_owner_id"))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("member_ids")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                .build();

        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("sample.proto")
                .setSyntax("proto3")
                .setPackage("sharedsync.test.v1")
                .addMessageType(message)
                .build();

        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("Sample");
    }

    public static class SampleDto {
        private UUID ownerId;
        private List<UUID> memberIds;
    }

    @Test
    @DisplayName("repeated 필드는 DTO 의 제네릭 요소 타입으로 되돌린다")
    void repeatedFieldsUseDeclaredElementType() throws Exception {
        Descriptor descriptor = sampleDescriptor();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        DynamicMessage message = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("owner_id"), first.toString())
                .addRepeatedField(descriptor.findFieldByName("member_ids"), first.toString())
                .addRepeatedField(descriptor.findFieldByName("member_ids"), second.toString())
                .build();

        SampleDto dto = DtoProtoMapper.toDto(message, SampleDto.class);

        assertEquals(first, dto.ownerId);
        // 요소 타입을 Object 로 넘기면 여기에 String 이 담긴다. 제네릭 소거 때문에 담기는 순간은
        // 통과하고, 꺼내 쓰는 쪽에서 ClassCastException 이 난다.
        assertTrue(dto.memberIds.get(0) instanceof UUID,
                "repeated 요소가 문자열인 채로 들어오면 안 된다: " + dto.memberIds.get(0).getClass());
        assertEquals(List.of(first, second), dto.memberIds);
    }

    @Test
    @DisplayName("미설정 optional 필드는 건드리지 않는다 (null-skip 부분 병합)")
    void unsetOptionalFieldStaysNull() throws Exception {
        Descriptor descriptor = sampleDescriptor();
        DynamicMessage message = DynamicMessage.newBuilder(descriptor).build();

        SampleDto dto = DtoProtoMapper.toDto(message, SampleDto.class);

        assertNull(dto.ownerId, "미설정과 빈 값을 구분하지 못하면 부분 업데이트가 데이터를 지운다");
    }
}
