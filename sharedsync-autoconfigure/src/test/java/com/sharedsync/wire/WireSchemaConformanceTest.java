package com.sharedsync.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.sharedsync.shared.codec.DtoProtoMapper;

import sharedsync.dto.WireGadgetDto;
import sharedsync.proto.SyncSchema;

/**
 * 애노테이션 프로세서가 만드는 세 산출물이 서로 어긋나지 않는지 기계적으로 대조한다:
 * .proto 텍스트 / FileDescriptorProto / 생성된 DTO 클래스.
 *
 * 어긋나면 스키마에는 있는데 페이로드에는 없는 필드가 생겨 **조용히** 데이터가 사라진다.
 *
 * 이 테스트는 프레임워크 자신의 테스트 픽스처({@link WireGadget})로 돈다. 예전에는 생성 코드가
 * 소비 앱에만 존재해서 이 검증이 앱 저장소에 있었고, 프레임워크를 고쳐도 앱을 빌드해야
 * 회귀를 알 수 있었다.
 */
class WireSchemaConformanceTest {

    private static final FileDescriptor FILE = loadFile();

    private static FileDescriptor loadFile() {
        try {
            return FileDescriptor.buildFrom(
                    FileDescriptorProto.parseFrom(Base64.getDecoder().decode(SyncSchema.DESCRIPTOR_BASE64)),
                    new FileDescriptor[0]);
        } catch (Exception e) {
            throw new IllegalStateException("생성된 디스크립터를 로드할 수 없다", e);
        }
    }

    @Test
    @DisplayName("스키마 해시는 .proto 텍스트에서 계산된 값과 일치한다")
    void schemaHashMatchesProtoText() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(SyncSchema.PROTO_TEXT.getBytes(StandardCharsets.UTF_8));

        assertThat(SyncSchema.SCHEMA_HASH)
                .as("클라이언트는 서버가 내려준 .proto 바이트를 해시해 비교한다. 디스크립터에서 뽑으면 "
                        + "protoc 산출본과 인코딩이 달라 항상 불일치한다.")
                .isEqualTo(HexFormat.of().formatHex(Arrays.copyOf(digest, 8)));
    }

    @Test
    @DisplayName("생성된 DTO 의 필드 집합과 proto 메시지의 필드 집합이 정확히 일치한다")
    void dtoFieldsMatchProtoFields() {
        Set<String> protoFields = FILE.findMessageTypeByName("WireGadget").getFields().stream()
                .map(FieldDescriptor::getName)
                .collect(Collectors.toSet());

        Set<String> dtoFields = Arrays.stream(WireGadgetDto.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(f -> toSnakeCase(f.getName()))
                .collect(Collectors.toSet());

        assertThat(protoFields)
                .as("한쪽에만 있는 필드는 '스키마에는 있는데 페이로드에는 없는' 상태가 되어 조용히 사라진다")
                .isEqualTo(dtoFields);
    }

    @Test
    @DisplayName("참조 타입은 명시적 presence 를 갖고 자바 원시 타입은 갖지 않는다")
    void referenceTypesHavePresencePrimitivesDoNot() {
        Descriptor gadget = FILE.findMessageTypeByName("WireGadget");

        assertThat(gadget.findFieldByName("label").hasPresence())
                .as("EntityDtoConverter.mergeDto 가 null 필드를 건너뛰는 부분 병합이라, presence 가 없으면 "
                        + "클라이언트가 보내지 않은 필드가 빈 값으로 덮여 데이터가 사라진다")
                .isTrue();
        assertThat(gadget.findFieldByName("weight").hasPresence()).isTrue();
        assertThat(gadget.findFieldByName("shape").hasPresence()).isTrue();

        assertThat(gadget.findFieldByName("slot_count").hasPresence())
                .as("자바 원시 타입은 DTO 에서 null 이 될 수 없어 항상 덮어쓴다. proto 도 같은 의미여야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("DTO 왕복: UUID/BigDecimal/LocalTime/enum 이 값 손실 없이 복원된다")
    void dtoRoundTripPreservesValues() {
        Descriptor descriptor = FILE.findMessageTypeByName("WireGadget");

        WireGadgetDto original = new WireGadgetDto();
        setField(original, "gadgetId", UUID.randomUUID());
        setField(original, "label", "라벨");
        setField(original, "slotCount", 7);
        setField(original, "weight", 42);
        setField(original, "price", new BigDecimal("12345.67890123"));
        setField(original, "opensAt", LocalTime.of(9, 30, 15));
        setField(original, "shape", GadgetShape.SQUARE);

        DynamicMessage encoded = DtoProtoMapper.toMessage(original, descriptor);
        WireGadgetDto restored = DtoProtoMapper.toDto(encoded, WireGadgetDto.class);

        assertThat(getField(restored, "gadgetId")).isEqualTo(getField(original, "gadgetId"));
        assertThat(getField(restored, "label")).isEqualTo("라벨");
        assertThat(getField(restored, "slotCount")).isEqualTo(7);
        assertThat(getField(restored, "weight")).isEqualTo(42);
        assertThat(getField(restored, "price"))
                .as("BigDecimal 은 문자열로 왕복해야 numeric 정밀도가 보존된다")
                .isEqualTo(new BigDecimal("12345.67890123"));
        assertThat(getField(restored, "opensAt")).isEqualTo(LocalTime.of(9, 30, 15));
        assertThat(getField(restored, "shape")).isEqualTo(GadgetShape.SQUARE);
    }

    @Test
    @DisplayName("핵심: 일부 필드만 설정한 부분 업데이트에서 나머지는 null 로 남는다 (기본값으로 덮이지 않음)")
    void partialUpdateLeavesUnsetFieldsNull() {
        Descriptor descriptor = FILE.findMessageTypeByName("WireGadget");

        WireGadgetDto partial = new WireGadgetDto();
        setField(partial, "label", "새 라벨");

        WireGadgetDto restored = DtoProtoMapper.toDto(
                DtoProtoMapper.toMessage(partial, descriptor), WireGadgetDto.class);

        assertThat(getField(restored, "label")).isEqualTo("새 라벨");
        assertThat(getField(restored, "price"))
                .as("여기서 0 이나 빈 문자열이 나오면 서버가 그 값으로 덮어써 데이터가 사라진다")
                .isNull();
        assertThat(getField(restored, "opensAt")).isNull();
        assertThat(getField(restored, "shape")).isNull();
        assertThat(getField(restored, "gadgetId")).isNull();
    }

    @Test
    @DisplayName("엔벨로프 메시지가 전부 정의되어 있고 payload oneof arm 이 엔티티 수와 맞는다")
    void envelopeMessagesExist() {
        for (String name : List.of("ClientFrame", "ServerFrame", "Join", "Ping", "Pong", "Error",
                "Hello", "SyncRequest", "SyncEvent", "PresenceEvent", "PresenceUser")) {
            assertThat(FILE.findMessageTypeByName(name)).as(name + " 이 없다").isNotNull();
        }

        long entityCount = FILE.getMessageTypes().stream()
                .filter(m -> m.getName().endsWith("List"))
                .count();
        assertThat(FILE.findMessageTypeByName("SyncRequest").getOneofs().get(0).getFieldCount())
                .as("엔티티가 추가됐는데 oneof arm 이 안 늘면 그 엔티티는 wire 로 나갈 수 없다")
                .isEqualTo((int) entityCount);
    }

    // ==========================================
    // 생성된 DTO 는 private 필드만 있어 리플렉션으로 다룬다
    // ==========================================

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name + " 설정 실패", e);
        }
    }

    private static Object getField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name + " 조회 실패", e);
        }
    }

    private static String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
