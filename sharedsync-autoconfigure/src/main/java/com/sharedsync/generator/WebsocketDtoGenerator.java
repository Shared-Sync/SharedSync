package com.sharedsync.generator;

import java.io.IOException;
import java.io.Writer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileObject;

import com.sharedsync.generator.Generator.CacheInformation;

public class WebsocketDtoGenerator {

    private static final String OBJECT_NAME = "wsdto";

    public static void initialize(CacheInformation cacheInfo) {
        String entity = cacheInfo.getEntityName();

        cacheInfo.setRequestClassName("W" + entity + "Request");
        cacheInfo.setResponseClassName("W" + entity + "Response");

        // 기존 생성 구조 따라감
        cacheInfo.setRequestPath(cacheInfo.getBasicPackagePath() + "." + OBJECT_NAME);
        cacheInfo.setResponsePath(cacheInfo.getBasicPackagePath() + "." + OBJECT_NAME);
    }

    public static boolean process(CacheInformation cacheInfo, ProcessingEnvironment processingEnv) {
        generateRequestDto(cacheInfo, processingEnv);
        generateResponseDto(cacheInfo, processingEnv);
        return true;
    }

    // ============================================
    // REQUEST DTO 생성
    // ============================================
    private static void generateRequestDto(CacheInformation cacheInfo, ProcessingEnvironment env) {

        String entity = cacheInfo.getEntityName();
        String dtoName = entity + "Dto";
        String fieldName = decapitalizeFirst(dtoName); // Example: PlanDto -> planDto

        StringBuilder source = new StringBuilder();

        source.append("package ").append(cacheInfo.getRequestPath()).append(";\n\n");
        source.append("import java.util.List;\n");
        source.append("import com.sharedsync.shared.dto.WRequest;\n");
        source.append("import ").append(cacheInfo.getDtoPath()).append(".").append(dtoName).append(";\n");
        source.append("import com.fasterxml.jackson.annotation.JsonFormat;\n\n");
        source.append("import lombok.Getter;\n");
        source.append("import lombok.Setter;\n\n");

        source.append("@Getter\n");
        source.append("@Setter\n");
        source.append("public class ").append(cacheInfo.getRequestClassName()).append(" extends WRequest {\n\n");
        source.append("    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)\n");
        source.append("    private List<").append(dtoName).append("> ").append(fieldName).append("s").append(";\n\n");
        source.append("}\n");

        writeToFile(env, cacheInfo.getRequestPath(), cacheInfo.getRequestClassName(), source.toString());
    }

    // ============================================
    // RESPONSE DTO 생성
    // ============================================
    private static void generateResponseDto(CacheInformation cacheInfo, ProcessingEnvironment env) {

        String entity = cacheInfo.getEntityName();
        String dtoName = entity + "Dto";
        String fieldName = decapitalizeFirst(dtoName);

        StringBuilder source = new StringBuilder();

        source.append("package ").append(cacheInfo.getResponsePath()).append(";\n\n");
        source.append("import java.util.List;\n");
        source.append("import com.sharedsync.shared.dto.WResponse;\n");
        source.append("import ").append(cacheInfo.getDtoPath()).append(".").append(dtoName).append(";\n\n");
        source.append("import lombok.Getter;\n");
        source.append("import lombok.Setter;\n\n");

        source.append("@Getter\n");
        source.append("@Setter\n");
        source.append("public class ").append(cacheInfo.getResponseClassName()).append(" extends WResponse {\n\n");
        source.append("    private List<").append(dtoName).append("> ").append(fieldName).append("s").append(";\n\n");
        source.append("}\n");

        writeToFile(env, cacheInfo.getResponsePath(), cacheInfo.getResponseClassName(), source.toString());
    }

    // ============================================
    // 파일 생성 공통 함수
    // ============================================
    private static void writeToFile(ProcessingEnvironment env, String path, String className, String source) {
        try {
            JavaFileObject file = env.getFiler().createSourceFile(path + "." + className);
            Writer writer = file.openWriter();
            writer.write(source);
            writer.close();
        } catch (IOException e) {
            env.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Failed to generate WebSocket DTO: " + className + " | " + e.getMessage());
        }
    }

    // ============================================
    // util
    // ============================================
    private static String decapitalizeFirst(String str) {
        if (str == null || str.isEmpty()) return "";
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }
}
