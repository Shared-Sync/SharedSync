package com.sharedsync.shared.codec;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;

/**
 * 생성된 DTO 클래스 <-> DynamicMessage 리플렉션 매핑.
 *
 * 엔티티별 코덱 클래스를 생성하는 대신 리플렉션을 쓰는 이유: 생성 코드가 줄면 스키마와 어긋날
 * 표면도 줄고, 이미 EntityDtoConverter/CacheDto 가 같은 방식으로 필드에 접근하고 있어 일관적이다.
 *
 * 필드 대응은 이름으로 한다 — DTO 의 camelCase 필드명을 snake_case 로 바꾼 것이 proto 필드명이다
 * (ProtoSchemaGenerator 가 같은 규칙으로 스키마를 만든다).
 */
public final class DtoProtoMapper {

    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private DtoProtoMapper() {
    }

    public static DynamicMessage toMessage(Object dto, Descriptor descriptor) {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
        Map<String, Field> fields = fieldsOf(dto.getClass());

        for (FieldDescriptor fd : descriptor.getFields()) {
            Field field = fields.get(fd.getName());
            if (field == null) {
                continue;
            }
            try {
                Object value = field.get(dto);
                if (value == null) {
                    // 미설정으로 남긴다. optional 필드라 수신측에서 hasField=false 가 되고,
                    // EntityDtoConverter 의 null-skip 부분 병합이 그대로 성립한다.
                    continue;
                }
                if (fd.isRepeated()) {
                    List<Object> converted = new ArrayList<>();
                    for (Object element : (Iterable<?>) value) {
                        converted.add(ProtoValueConverter.toProto(element, fd));
                    }
                    builder.setField(fd, converted);
                } else {
                    Object converted = ProtoValueConverter.toProto(value, fd);
                    if (converted != null) {
                        builder.setField(fd, converted);
                    }
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("DTO 필드 읽기 실패: " + fd.getName(), e);
            }
        }
        return builder.build();
    }

    public static <T> T toDto(DynamicMessage message, Class<T> dtoClass) {
        try {
            T dto = dtoClass.getDeclaredConstructor().newInstance();
            Map<String, Field> fields = fieldsOf(dtoClass);

            for (FieldDescriptor fd : message.getDescriptorForType().getFields()) {
                Field field = fields.get(fd.getName());
                if (field == null) {
                    continue;
                }
                if (fd.isRepeated()) {
                    int count = message.getRepeatedFieldCount(fd);
                    if (count == 0) {
                        continue;
                    }
                    // 요소 타입을 제네릭에서 뽑아 넘긴다. Object.class 로 넘기면 ProtoValueConverter 가
                    // 되돌릴 대상을 몰라 UUID·enum·BigDecimal 컬렉션이 문자열인 채로 DTO 에 들어간다
                    // (넣는 순간은 지네릭 소거 때문에 통과하고, 나중에 꺼내 쓸 때 ClassCastException).
                    Class<?> elementType = elementTypeOf(field);
                    List<Object> values = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        values.add(ProtoValueConverter.toJava(message.getRepeatedField(fd, i), elementType));
                    }
                    field.set(dto, values);
                    continue;
                }

                // 명시적 presence 가 있는 필드는 미설정이면 건드리지 않는다 — null 로 남아야
                // 부분 업데이트의 null-skip 병합이 동작한다.
                if (fd.hasPresence() && !message.hasField(fd)) {
                    continue;
                }
                Object value = ProtoValueConverter.toJava(message.getField(fd), field.getType());
                if (value != null) {
                    field.set(dto, value);
                }
            }
            return dto;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("DTO 생성 실패: " + dtoClass.getName(), e);
        }
    }

    /** {@code List<UUID>} 의 UUID. 알아낼 수 없으면 Object (변환 없이 그대로). */
    private static Class<?> elementTypeOf(Field field) {
        if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterized) {
            java.lang.reflect.Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> type) {
                return type;
            }
        }
        return Object.class;
    }

    /** proto 필드명(snake_case) -> DTO Field */
    private static Map<String, Field> fieldsOf(Class<?> dtoClass) {
        return FIELD_CACHE.computeIfAbsent(dtoClass, cls -> {
            Map<String, Field> map = new LinkedHashMap<>();
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    map.putIfAbsent(toSnakeCase(f.getName()), f);
                }
            }
            return map;
        });
    }

    static String toSnakeCase(String camel) {
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
