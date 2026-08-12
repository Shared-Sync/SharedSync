package com.sharedsync.generator;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import com.sharedsync.generator.Generator.CacheInformation;

/**
 * 생성된 캐시 DTO 목록을 상수로 남긴다.
 *
 * RedisConfig 가 DTO 마다 RedisTemplate 빈을 등록하는데, 그 목록을 런타임에 org.reflections 로
 * {@code sharedsync.dto} 패키지를 스캔해 얻고 있었다. 목록은 컴파일 시점에 이미 확정돼 있고,
 * 이 스캔 하나 때문에 소비 앱의 런타임 클래스패스에 reflections 가 얹혔다.
 */
final class CacheDtoRegistryGenerator {

    static final String REGISTRY_CLASS = "sharedsync.dto.CacheDtoRegistry";

    private CacheDtoRegistryGenerator() {
    }

    static void generate(List<CacheInformation> cacheInfoList, ProcessingEnvironment env) {
        if (cacheInfoList.isEmpty()) {
            return;
        }

        String entries = cacheInfoList.stream()
                .map(info -> "        \"" + info.getDtoPath() + "." + info.getDtoClassName() + "\"")
                .collect(Collectors.joining(",\n"));

        String source = "package sharedsync.dto;\n\n"
                + "/**\n"
                + " * SharedSync annotation processor 생성물. 손으로 고치지 말 것.\n"
                + " *\n"
                + " * 런타임 패키지 스캔을 대신한다.\n"
                + " */\n"
                + "public final class CacheDtoRegistry {\n\n"
                + "    private CacheDtoRegistry() {}\n\n"
                + "    public static final String[] DTO_CLASSES = {\n"
                + entries + "\n"
                + "    };\n"
                + "}\n";

        try {
            JavaFileObject file = env.getFiler().createSourceFile(REGISTRY_CLASS);
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException e) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[SharedSync] CacheDtoRegistry 생성 실패: " + e.getMessage());
        }
    }
}
