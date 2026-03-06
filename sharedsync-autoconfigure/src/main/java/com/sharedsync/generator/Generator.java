package com.sharedsync.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;

import com.sharedsync.shared.annotation.CacheEntity;

import lombok.Getter;
import lombok.Setter;

@SupportedAnnotationTypes("com.sharedsync.shared.annotation.CacheEntity")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class Generator extends AbstractProcessor {

    List<CacheInformation> cacheInfoList;
    private static boolean presenceControllerGenerated = false;


    public Generator() {
        cacheInfoList = new ArrayList<>();
    }

    // ================================
    // 내부 데이터 구조
    // ================================
    @Getter
    @Setter
    public class FieldInfo {
        private String name;
        private String type;
        private boolean isManyToOne;
        private boolean isOneToMany;
        private boolean isManyToMany;
        private boolean isOneToOne;
        private boolean isIgnored;
        private String originalType;
        private String collectionPath;

        public FieldInfo(String name, String type, boolean isManyToOne, boolean isOneToMany, boolean isManyToMany, boolean isOneToOne, boolean isIgnored, String collectionPath) {
            this.name = name;
            this.originalType = type;
            this.type = normalizeType(type);
            this.isManyToOne = isManyToOne;
            this.isOneToMany = isOneToMany;
            this.isManyToMany = isManyToMany;
            this.isOneToOne = isOneToOne;
            this.isIgnored = isIgnored;
            this.collectionPath = collectionPath;
        }
    }

    @Getter
    @Setter
    public class RelatedEntity {
        private String entityPath;
        private String entityIdType;
        private String entityIdName;
        private String cacheEntityIdName;
        private String entityIdOriginalType;
        private String tableName;
        private boolean isCacheEntity;
    }

    @Getter
    @Setter
    public class CacheInformation {

        private String entityName;
        private String idType;
        private String idName;
        private String idOriginalType;
        private String basicPackagePath;
        private String entityPath;

        private List<FieldInfo> entityFields;

        // dto
        private String dtoClassName;
        private String dtoPath;

        // cache
        private String cacheClassName;
        private String cachePath;

        // controller
        private String controllerClassName;
        private String controllerPath;

        // service
        private String serviceClassName;
        private String servicePath;

        // request
        private String requestClassName;
        private String requestPath;

        // response
        private String responseClassName;
        private String responsePath;

        List<RelatedEntity> relatedEntities;

        public CacheInformation() {
            basicPackagePath = "sharedsync";
            relatedEntities = new ArrayList<>();
            entityFields = new ArrayList<>();
        }

        public void addRelatedEntity(RelatedEntity relatedEntity) {
            relatedEntities.add(relatedEntity);
        }

        public void addEntityField(FieldInfo fieldInfo) {
            this.entityFields.add(fieldInfo);
        }
    }

    // ================================
    // Primitive → Wrapper 변환기
    // ================================
    public static String normalizeType(String type) {

        // 패키지 제거
//        String pure = removePath(type);

        return switch (type) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "short" -> "Short";
            case "byte" -> "Byte";
            case "boolean" -> "Boolean";
            case "float" -> "Float";
            case "double" -> "Double";
            case "char" -> "Character";
            default -> type; // wrapper or class name 그대로
        };
    }

    public static String denormalizeType(String normalizedType, String originalType) {
        if (originalType != null && isPrimitiveType(originalType)) {
            return originalType;
        }
        return normalizedType;
    }

    private static boolean isPrimitiveType(String type) {
        return switch (type) {
            case "int", "long", "short", "byte", "boolean", "float", "double", "char" -> true;
            default -> false;
        };
    }

    // ================================
    // Annotation Processor 메인 처리
    // ================================
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        for (Element element : roundEnv.getElementsAnnotatedWith(CacheEntity.class)) {

            CacheInformation cacheInfo = new CacheInformation();

            String entityName = element.getSimpleName().toString();
            cacheInfo.setEntityName(entityName);
            cacheInfo.setEntityPath(element.asType().toString());

            // -------------------------------
            // 엔티티의 모든 필드 탐색
            // -------------------------------
            for (Element field : element.getEnclosedElements()) {

                if (field.getKind().isField()) {
                    boolean isManyToOne = field.getAnnotation(jakarta.persistence.ManyToOne.class) != null;
                    boolean isOneToMany = field.getAnnotation(jakarta.persistence.OneToMany.class) != null;
                    boolean isManyToMany = field.getAnnotation(jakarta.persistence.ManyToMany.class) != null;
                    boolean isOneToOne = field.getAnnotation(jakarta.persistence.OneToOne.class) != null;
                    boolean isIgnored = field.getAnnotation(com.sharedsync.shared.annotation.IgnoreShared.class) != null;
                    String type;
                    String collectionPath = "";
                    if (isOneToMany || isManyToMany) {
                        // OneToMany/ManyToMany 컬렉션 타입 처리
                        if (field.asType() instanceof DeclaredType declaredType) {
                            List<? extends javax.lang.model.type.TypeMirror> typeArgs = declaredType.getTypeArguments();
                            collectionPath = declaredType.asElement().toString();
                            if (!typeArgs.isEmpty()) {
                                type = typeArgs.get(0).toString();
                            } else {
                                type = "java.lang.Object"; // 기본값 처리
                            }
                        } else {
                            type = "java.lang.Object"; // 기본값 처리
                        }
                    } else {
                        type = field.asType().toString();
                    }
                    cacheInfo.addEntityField(
                            new FieldInfo(field.getSimpleName().toString(), type, isManyToOne, isOneToMany, isManyToMany, isOneToOne, isIgnored, collectionPath)
                    );
                }

                // ID 필드 처리
                if (field.getAnnotation(jakarta.persistence.Id.class) != null) {
                    cacheInfo.setIdType(normalizeType(field.asType().toString()));
                    cacheInfo.setIdName(field.getSimpleName().toString());
                    cacheInfo.setIdOriginalType(field.asType().toString());
                }

                // ManyToOne 연관 엔티티 처리, OneToMany 연관 엔티티 처리, ManyToMany 연관 엔티티 처리
                if (field.getAnnotation(jakarta.persistence.ManyToOne.class) != null ||
                        field.getAnnotation(jakarta.persistence.OneToMany.class) != null ||
                        field.getAnnotation(jakarta.persistence.ManyToMany.class) != null ||
                        field.getAnnotation(jakarta.persistence.OneToOne.class) != null) {
                    RelatedEntity related = new RelatedEntity();
                    if(field.getAnnotation(jakarta.persistence.ManyToOne.class) != null ||
                            field.getAnnotation(jakarta.persistence.OneToOne.class) != null){
                        related.setEntityPath(field.asType().toString());
                    }
                    if(field.getAnnotation(jakarta.persistence.OneToMany.class) != null ||
                            field.getAnnotation(jakarta.persistence.ManyToMany.class) != null){
                        // OneToMany/ManyToMany 컬렉션 타입 처리
                        if (field.asType() instanceof DeclaredType declaredType) {
                            List<? extends javax.lang.model.type.TypeMirror> typeArgs = declaredType.getTypeArguments();
                            if (!typeArgs.isEmpty()) {
                                related.setEntityPath(typeArgs.get(0).toString());
                            } else {
                                related.setEntityPath("java.lang.Object"); // 기본값 처리
                            }
                        } else {
                            related.setEntityPath("java.lang.Object"); // 기본값 처리
                        }
                    }


                    String relatedEntityName = removePath(field.asType().toString());
                    if(field.getAnnotation(jakarta.persistence.OneToMany.class) != null ||
                            field.getAnnotation(jakarta.persistence.ManyToMany.class) != null){
                        relatedEntityName = relatedEntityName.replace(">", "");
                    }
                    //
                    System.out.println("EntityName:"+entityName+" Related Entity Detected: " + relatedEntityName);

                    // Determine the correct declared type to inspect for @Id
                    DeclaredType targetDeclared = null;
                    if (field.getAnnotation(jakarta.persistence.ManyToOne.class) != null ||
                            field.getAnnotation(jakarta.persistence.OneToOne.class) != null) {
                        if (field.asType() instanceof DeclaredType dt) {
                            targetDeclared = dt;
                        }
                    } else if (field.getAnnotation(jakarta.persistence.OneToMany.class) != null ||
                            field.getAnnotation(jakarta.persistence.ManyToMany.class) != null) {
                        if (field.asType() instanceof DeclaredType dt) {
                            List<? extends javax.lang.model.type.TypeMirror> typeArgs = dt.getTypeArguments();
                            if (!typeArgs.isEmpty() && typeArgs.get(0) instanceof DeclaredType elemDt) {
                                targetDeclared = elemDt;
                            }
                        }
                    }

                    if (targetDeclared != null) {
                        TypeElement targetElement = (TypeElement) targetDeclared.asElement();
                        related.setCacheEntity(targetElement.getAnnotation(CacheEntity.class) != null);
                        
                        // 테이블 이름 추출
                        jakarta.persistence.Table tableAnn = targetElement.getAnnotation(jakarta.persistence.Table.class);
                        if (tableAnn != null && !tableAnn.name().isEmpty()) {
                            related.setTableName(tableAnn.name());
                        } else {
                            // 기본값: 클래스 이름을 snake_case로 (간단히 구현)
                            String simpleName = targetElement.getSimpleName().toString();
                            related.setTableName(toSnakeCase(simpleName));
                        }

                        for (Element rf : targetElement.getEnclosedElements()) {
                            if (rf.getAnnotation(jakarta.persistence.Id.class) != null) {
                                related.setEntityIdType(normalizeType(rf.asType().toString()));
                                related.setEntityIdName(rf.getSimpleName().toString());
                                related.setCacheEntityIdName(field.getSimpleName().toString()+"Id");
                                related.setEntityIdOriginalType(rf.asType().toString());
                                break;
                            }
                        }
                    }
                    cacheInfo.addRelatedEntity(related);
                }
            }

            cacheInfoList.add(cacheInfo);
            initialize(cacheInfo);

            // 실제 파일 생성기 호출
            CacheEntityGenerator.process(cacheInfo, processingEnv);
            DtoGenerator.process(cacheInfo, processingEnv);
            WebsocketDtoGenerator.process(cacheInfo, processingEnv);
            ServiceGenerator.process(cacheInfo, processingEnv);
            // Generate per-entity allArgsConstructor factory class under package sharedsync.allArgsConstructor
            EntityAllArgsConstructorGenerator.process(cacheInfo, processingEnv);
            // ⭐ PresenceController는 단 1회만 생성
            if (!presenceControllerGenerated) {
                PresenceControllerGenerator.generate(processingEnv);
                presenceControllerGenerated = true;
            }

        }

        if (roundEnv.processingOver()) {
            ControllerGenerator.generateUnified(cacheInfoList, processingEnv);
        }

        return false;
    }

    // ================================
    // Generator 초기화
    // ================================
    public static void initialize(CacheInformation cacheInfo) {
        EntityAllArgsConstructorGenerator.initialize(cacheInfo);
        CacheEntityGenerator.initialize(cacheInfo);
        DtoGenerator.initialize(cacheInfo);
        WebsocketDtoGenerator.initialize(cacheInfo);
        ControllerGenerator.initialize(cacheInfo);
        ServiceGenerator.initialize(cacheInfo);
    }

    // ================================
    // 유틸 메서드들
    // ================================
    public static String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) return "";
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    public static String decapitalizeFirst(String str) {
        if (str == null || str.isEmpty()) return "";
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }

    public static String toSnakeCase(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder sb = new StringBuilder();
        char[] chars = input.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String removePath(String fullPath) {
        if(fullPath == null) return null;
        if (fullPath.contains(".")) {
            String[] s = fullPath.split("\\.");
            return s[s.length - 1];
        }
        return fullPath;
    }
}