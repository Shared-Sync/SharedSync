package com.sharedsync.generator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * 자바 타입 -> proto3 타입 매핑.
 *
 * 문자열로 내보내는 타입들(UUID/BigDecimal/LocalTime/LocalDate)은 의도적인 선택이다:
 *  - BigDecimal: 컬럼이 numeric(10,8)/(11,8) 이라 double 로 가면 정밀도·scale 이 흔들린다.
 *    toPlainString() <-> new BigDecimal(s) 로 무손실 왕복한다.
 *  - LocalTime: 기존 LocalTime24Deserializer 가 "24:00:00" -> 23:59:59 로 정규화하는 quirk 가
 *    있어서 초 단위 정수로 바꾸면 그 동작이 바뀐다. "HH:mm:ss" 문자열을 유지해 동작을 보존한다.
 */
public final class ProtoTypeMapper {

    /** 이 스키마에서 참조된 자바 enum -> proto enum 이름 */
    private final Map<String, String> enumTypes = new LinkedHashMap<>();

    private final ProcessingEnvironment env;

    public ProtoTypeMapper(ProcessingEnvironment env) {
        this.env = env;
    }

    public Map<String, String> getEnumTypes() {
        return enumTypes;
    }

    /**
     * 자바 타입명을 proto 타입명으로 변환한다. enum 이면 부수적으로 enumTypes 에 등록된다.
     */
    public String toProtoType(String javaType) {
        String simple = Generator.removePath(javaType);

        switch (simple) {
            case "String":
                return "string";
            case "UUID":
                return "string";
            case "BigDecimal":
                return "string";
            case "LocalTime":
            case "LocalDate":
            case "LocalDateTime":
            case "Instant":
                return "string";
            case "Long":
            case "long":
                return "int64";
            case "Integer":
            case "int":
                return "int32";
            case "Short":
            case "short":
                return "int32";
            case "Boolean":
            case "boolean":
                return "bool";
            case "Double":
            case "double":
                return "double";
            case "Float":
            case "float":
                return "float";
            default:
                break;
        }

        String enumName = tryResolveEnum(javaType);
        if (enumName != null) {
            return enumName;
        }

        // 알 수 없는 타입은 문자열로 떨어뜨린다. 스키마에 드러나므로 리뷰에서 잡힌다.
        return "string";
    }

    /**
     * 자바 enum 이면 proto enum 이름을 반환하고 등록한다. 아니면 null.
     */
    private String tryResolveEnum(String javaType) {
        TypeElement element = env.getElementUtils().getTypeElement(javaType);
        if (element == null || element.getKind() != ElementKind.ENUM) {
            return null;
        }
        String protoEnumName = Generator.removePath(javaType);
        enumTypes.putIfAbsent(javaType, protoEnumName);
        return protoEnumName;
    }

    /**
     * 자바 enum 의 상수 목록.
     */
    public List<String> enumConstants(String javaType) {
        TypeElement element = env.getElementUtils().getTypeElement(javaType);
        if (element == null) {
            return List.of();
        }
        return element.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                .map(Element::getSimpleName)
                .map(Object::toString)
                .toList();
    }

    /**
     * buf lint STANDARD 는 enum 값이 enum 이름을 SCREAMING_SNAKE 로 바꾼 접두사를 갖고,
     * 0 번은 반드시 &lt;ENUM_NAME&gt;_UNSPECIFIED 이길 요구한다.
     */
    public static String enumValuePrefix(String protoEnumName) {
        return WireField.toSnakeCase(protoEnumName).toUpperCase();
    }
}
