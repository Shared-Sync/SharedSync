package com.sharedsync.shared.codec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

/**
 * DTO 필드값 <-> protobuf 필드값 변환.
 *
 * 문자열로 내보내는 타입들은 ProtoTypeMapper 의 매핑 규칙과 짝을 이룬다:
 *  - BigDecimal: numeric(10,8)/(11,8) 정밀도를 지키려고 toPlainString 왕복
 *  - LocalTime: 기존 LocalTime24Deserializer 의 "24:00:00" -> 23:59:59 정규화를 그대로 재현
 */
public final class ProtoValueConverter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ProtoValueConverter() {
    }

    /** 자바 값 -> protobuf 값. null 이면 null(= 필드 미설정). */
    public static Object toProto(Object javaValue, FieldDescriptor fd) {
        if (javaValue == null) {
            return null;
        }
        if (javaValue instanceof UUID uuid) {
            return uuid.toString();
        }
        if (javaValue instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (javaValue instanceof LocalTime time) {
            return time.format(TIME);
        }
        if (javaValue instanceof LocalDate date) {
            return date.toString();
        }
        if (javaValue instanceof Enum<?> e) {
            return enumValue(fd, e.name());
        }
        if (javaValue instanceof Integer || javaValue instanceof Long
                || javaValue instanceof Boolean || javaValue instanceof Double
                || javaValue instanceof Float || javaValue instanceof String) {
            return coerceNumeric(javaValue, fd);
        }
        if (javaValue instanceof Short s) {
            return coerceNumeric(s.intValue(), fd);
        }
        return javaValue.toString();
    }

    /** protobuf 값 -> 자바 값. 대상 타입에 맞춰 되돌린다. */
    public static Object toJava(Object protoValue, Class<?> targetType) {
        if (protoValue == null) {
            return null;
        }
        if (targetType == UUID.class) {
            String s = protoValue.toString();
            return s.isEmpty() ? null : UUID.fromString(s);
        }
        if (targetType == BigDecimal.class) {
            String s = protoValue.toString();
            return s.isEmpty() ? null : new BigDecimal(s);
        }
        if (targetType == LocalTime.class) {
            String s = protoValue.toString();
            if (s.isEmpty()) {
                return null;
            }
            // "24:00:00" 은 LocalTime 범위 밖이다. 기존 LocalTime24Deserializer 와 동일하게 처리한다.
            if (s.startsWith("24:")) {
                return LocalTime.of(23, 59, 59);
            }
            return LocalTime.parse(s, DateTimeFormatter.ISO_LOCAL_TIME);
        }
        if (targetType == LocalDate.class) {
            String s = protoValue.toString();
            return s.isEmpty() ? null : LocalDate.parse(s);
        }
        if (targetType.isEnum()) {
            String name = protoValue instanceof EnumValueDescriptor evd ? evd.getName() : protoValue.toString();
            return javaEnum(targetType, name);
        }
        if (targetType == Long.class || targetType == long.class) {
            return ((Number) protoValue).longValue();
        }
        if (targetType == Integer.class || targetType == int.class) {
            return ((Number) protoValue).intValue();
        }
        if (targetType == Short.class || targetType == short.class) {
            return ((Number) protoValue).shortValue();
        }
        if (targetType == Double.class || targetType == double.class) {
            return ((Number) protoValue).doubleValue();
        }
        if (targetType == Float.class || targetType == float.class) {
            return ((Number) protoValue).floatValue();
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            return protoValue;
        }
        if (targetType == String.class) {
            return protoValue.toString();
        }
        return protoValue;
    }

    private static Object coerceNumeric(Object value, FieldDescriptor fd) {
        return switch (fd.getJavaType()) {
            case INT -> ((Number) value).intValue();
            case LONG -> ((Number) value).longValue();
            case DOUBLE -> ((Number) value).doubleValue();
            case FLOAT -> ((Number) value).floatValue();
            case BOOLEAN, STRING -> value;
            default -> value;
        };
    }

    /**
     * 자바 enum 상수명 -> proto enum 값. 생성 규칙은 &lt;ENUM_NAME&gt;_&lt;CONSTANT&gt; 다.
     * 못 찾으면 0(UNSPECIFIED)을 돌려주고, 서버가 이를 거부한다.
     */
    private static EnumValueDescriptor enumValue(FieldDescriptor fd, String constantName) {
        String prefix = fd.getEnumType().getName()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
        EnumValueDescriptor found = fd.getEnumType().findValueByName(prefix + "_" + constantName);
        return found != null ? found : fd.getEnumType().findValueByNumber(0);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object javaEnum(Class<?> targetType, String protoValueName) {
        String prefix = targetType.getSimpleName()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase() + "_";
        String constant = protoValueName.startsWith(prefix)
                ? protoValueName.substring(prefix.length())
                : protoValueName;
        if ("UNSPECIFIED".equals(constant)) {
            return null;
        }
        try {
            return Enum.valueOf((Class<Enum>) targetType, constant);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
