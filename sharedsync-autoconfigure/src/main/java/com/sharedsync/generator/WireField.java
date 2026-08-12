package com.sharedsync.generator;

/**
 * DTO 한 필드의 wire 표현. DtoGenerator.writeDtoFields() 가 만들어내는 필드와 1:1 대응해야 한다.
 *
 * 둘이 어긋나면 스키마에는 있는데 DTO 에는 없는(또는 그 반대) 필드가 생기므로,
 * Backend-v2 의 컨포먼스 테스트가 생성된 DTO 의 선언 필드와 이 목록을 비교해 검증한다.
 */
public class WireField {

    /** DTO 필드명 (camelCase) */
    private final String javaName;

    /** DTO 필드의 자바 타입 (denormalize 된 것 — 원시 타입이면 int/boolean 등) */
    private final String javaType;

    /** Collection<X> 형태면 X, 아니면 null */
    private final String elementType;

    public WireField(String javaName, String javaType, String elementType) {
        this.javaName = javaName;
        this.javaType = javaType;
        this.elementType = elementType;
    }

    public String getJavaName() {
        return javaName;
    }

    public String getJavaType() {
        return javaType;
    }

    public String getElementType() {
        return elementType;
    }

    public boolean isRepeated() {
        return elementType != null;
    }

    /**
     * 자바 원시 타입 여부. proto3 에서 optional(명시적 presence)을 붙일지 결정한다.
     *
     * 원시 타입은 DTO 에서 절대 null 이 될 수 없어 EntityDtoConverter.mergeDto 의 null-skip 병합이
     * 적용되지 않는다(항상 덮어씀). proto 에서도 optional 을 빼서 같은 의미를 유지한다.
     */
    public boolean isJavaPrimitive() {
        return switch (javaType) {
            case "int", "long", "short", "byte", "boolean", "float", "double", "char" -> true;
            default -> false;
        };
    }

    /** proto 필드명 (lower_snake_case — buf lint STANDARD 요구) */
    public String getProtoName() {
        return toSnakeCase(javaName);
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
