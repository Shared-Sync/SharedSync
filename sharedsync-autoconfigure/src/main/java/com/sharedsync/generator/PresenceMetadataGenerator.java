package com.sharedsync.generator;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import com.sharedsync.shared.presence.annotation.PresenceRoot;
import com.sharedsync.shared.presence.annotation.PresenceUser;

/**
 * 프레즌스 대상 클래스를 컴파일 시점에 기록한다.
 *
 * 예전에는 런타임에 org.reflections 로 앱 패키지를 통째로 스캔해 {@code @PresenceRoot} /
 * {@code @PresenceUser} 를 찾았다. 세 가지가 문제였다:
 * <ul>
 *   <li>스캔 범위를 FrameworkContext 가 추측했다. 실패하면 fallback 이 {@code "com"} 이라
 *       클래스패스 전체를 훑는다.</li>
 *   <li>기동할 때마다 하는 일인데, 결과는 컴파일 시점에 이미 정해져 있다.</li>
 *   <li>애노테이션을 못 찾아도 조용히 넘어가 프레즌스가 통째로 죽은 채로 떴다.</li>
 * </ul>
 *
 * 애노테이션 프로세서는 어차피 이 프레임워크의 전제(컨트롤러·DTO·서비스가 전부 생성물)이므로,
 * 여기서 이름만 적어두면 런타임 스캔과 reflections 의존성이 함께 사라진다.
 */
@SupportedAnnotationTypes({
        "com.sharedsync.shared.presence.annotation.PresenceRoot",
        "com.sharedsync.shared.presence.annotation.PresenceUser"
})
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class PresenceMetadataGenerator extends AbstractProcessor {

    static final String METADATA_CLASS = "sharedsync.presence.PresenceMetadata";

    private final Set<String> roots = new LinkedHashSet<>();
    private final Set<String> users = new LinkedHashSet<>();
    private boolean written;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(PresenceRoot.class)) {
            roots.add(element.asType().toString());
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(PresenceUser.class)) {
            users.add(element.asType().toString());
        }

        // 마지막 라운드에 한 번만 기록한다. 라운드마다 만들면 Filer 가 재생성을 거부한다.
        if (roundEnv.processingOver() && !written && !(roots.isEmpty() && users.isEmpty())) {
            written = true;
            write();
        }
        return false;
    }

    private void write() {
        if (roots.size() > 1) {
            // 어느 것이 뽑히느냐에 따라 프레즌스 채널 이름이 달라진다. 런타임에 아무거나 고르던
            // 동작을 컴파일 오류로 끌어올린다.
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[SharedSync] @PresenceRoot 는 하나여야 한다. 발견된 것: " + roots);
            return;
        }

        String source = "package sharedsync.presence;\n\n"
                + "/**\n"
                + " * SharedSync annotation processor 생성물. 손으로 고치지 말 것.\n"
                + " *\n"
                + " * 런타임 클래스패스 스캔을 대신한다.\n"
                + " */\n"
                + "public final class PresenceMetadata {\n\n"
                + "    private PresenceMetadata() {}\n\n"
                + "    public static final String ROOT_CLASS = " + literal(first(roots)) + ";\n\n"
                + "    public static final String USER_CLASS = " + literal(first(users)) + ";\n"
                + "}\n";

        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(METADATA_CLASS);
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[SharedSync] PresenceMetadata 생성 실패: " + e.getMessage());
        }
    }

    private static String first(Set<String> values) {
        return values.isEmpty() ? null : values.iterator().next();
    }

    private static String literal(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
