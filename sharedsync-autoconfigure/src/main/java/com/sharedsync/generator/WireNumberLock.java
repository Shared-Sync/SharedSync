package com.sharedsync.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import com.sharedsync.generator.Generator.CacheInformation;

/**
 * 필드 번호 잠금.
 *
 * proto 필드 번호는 엔티티의 **선언 순서**로 부여된다. 그래서 엔티티 중간에 필드를 하나 끼우면
 * 그 뒤 필드들의 번호가 전부 밀리고, 클라이언트는 같은 바이트를 다른 필드로 읽는다. 값이 뒤섞일 뿐
 * 파싱은 성공하므로 예외도 나지 않는다.
 *
 * 스키마 해시가 런타임에 클라이언트를 거부해주긴 하지만, 그건 **배포한 뒤에** 알게 된다는 뜻이다.
 * 여기서는 앱이 커밋해둔 잠금 파일과 대조해 컴파일 시점에 잡는다.
 *
 * 형식은 한 줄에 하나: {@code Entity.field=번호}. JSON 을 쓰지 않는 이유는 APT 가 파서를 끌어다 쓰지
 * 않게 하려는 것이다(애노테이션 프로세서의 의존성은 소비 앱의 컴파일 클래스패스에 얹힌다).
 *
 * <ul>
 *   <li>앱이 {@code src/main/resources/sharedsync-wire.lock} 를 커밋해두면 그걸 기준으로 검증한다.</li>
 *   <li>없으면 검증을 건너뛰고, 생성된 최신 잠금 파일 위치를 NOTE 로 알린다.</li>
 *   <li>필드가 새로 늘어나는 것은 허용한다(뒤에 붙는 한). 기존 필드의 번호가 바뀌거나 사라지면 ERROR.</li>
 * </ul>
 */
final class WireNumberLock {

    static final String LOCK_FILE = "sharedsync-wire.lock";

    private WireNumberLock() {
    }

    /** 이번 컴파일에서 부여된 번호. {@code Entity.field -> number} */
    static Map<String, Integer> currentNumbers(List<CacheInformation> cacheInfoList) {
        Map<String, Integer> numbers = new LinkedHashMap<>();
        for (CacheInformation info : cacheInfoList) {
            int number = 1;
            for (WireField field : WireFieldResolver.resolve(info)) {
                numbers.put(info.getEntityName() + "." + field.getProtoName(), number++);
            }
        }
        return numbers;
    }

    /**
     * 커밋된 잠금과 대조한다. 어긋나면 ERROR 를 찍어 컴파일을 깬다.
     *
     * @param generatedUri 방금 생성한 잠금 파일의 위치. 여기서 프로젝트 루트를 거슬러 올라가
     *                     앱이 커밋해둔 잠금을 찾는다.
     */
    static void verify(Map<String, Integer> current, ProcessingEnvironment env, java.net.URI generatedUri) {
        Map<String, Integer> locked = readLock(env, generatedUri);
        if (locked == null) {
            env.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[SharedSync] " + LOCK_FILE + " 이 없어 필드 번호 검증을 건너뛴다. "
                            + "생성된 파일을 src/main/resources/ 에 복사해 커밋하면 이후 컴파일에서 "
                            + "번호가 밀리는 변경을 잡아준다.");
            return;
        }

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : locked.entrySet()) {
            Integer now = current.get(entry.getKey());
            if (now == null) {
                problems.add(entry.getKey() + " 가 사라졌다 (잠긴 번호 " + entry.getValue()
                        + "). 필드를 지우면 그 번호는 재사용하면 안 된다 — 구 클라이언트가 보낸 값이"
                        + " 새 필드로 읽힌다.");
            } else if (!now.equals(entry.getValue())) {
                problems.add(entry.getKey() + " 의 번호가 " + entry.getValue() + " -> " + now
                        + " 로 바뀌었다. 엔티티 중간에 필드를 끼웠거나 순서를 바꾼 것이다 —"
                        + " 새 필드는 맨 뒤에 선언할 것.");
            }
        }

        if (!problems.isEmpty()) {
            for (String problem : problems) {
                env.getMessager().printMessage(Diagnostic.Kind.ERROR, "[SharedSync] wire 호환성 위반: " + problem);
            }
        }
    }

    /**
     * 최신 상태의 잠금 파일을 생성물로 남긴다. 앱은 이걸 복사해 커밋한다.
     *
     * @return 기록한 파일의 URI. 커밋된 잠금을 찾는 기준점이 된다.
     */
    static java.net.URI write(Map<String, Integer> current, ProcessingEnvironment env) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SharedSync wire 필드 번호 잠금. 생성물이지만 커밋 대상이다.\n");
        sb.append("# src/main/resources/").append(LOCK_FILE).append(" 로 복사해두면 다음 컴파일부터\n");
        sb.append("# 번호가 밀리는 변경(엔티티 중간 필드 삽입 등)을 컴파일 시점에 잡는다.\n");
        for (Map.Entry<String, Integer> entry : current.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        try {
            FileObject file = env.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", LOCK_FILE);
            try (Writer writer = file.openWriter()) {
                writer.write(sb.toString());
            }
            return file.toUri();
        } catch (IOException e) {
            env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[SharedSync] " + LOCK_FILE + " 기록 실패: " + e.getMessage());
            return null;
        }
    }

    /** 앱이 커밋해둔 잠금 파일. 없으면 null. */
    private static Map<String, Integer> readLock(ProcessingEnvironment env, java.net.URI generatedUri) {
        java.io.File committed = findCommittedLock(generatedUri);
        if (committed == null) {
            return null;
        }
        Map<String, Integer> locked = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(committed), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int equals = trimmed.lastIndexOf('=');
                if (equals <= 0) {
                    continue;
                }
                try {
                    locked.put(trimmed.substring(0, equals).trim(),
                            Integer.parseInt(trimmed.substring(equals + 1).trim()));
                } catch (NumberFormatException ignored) {
                    // 손으로 고치다 깨진 줄. 검증 대상에서만 빠진다.
                }
            }
        } catch (IOException e) {
            return null;
        }
        return locked;
    }

    /**
     * 앱이 커밋해둔 {@code src/main/resources/sharedsync-wire.lock} 를 찾는다.
     *
     * Filer.getResource(CLASS_PATH/SOURCE_PATH) 로는 찾을 수 없다 — Gradle 에서 프로젝트 자신의
     * 리소스는 컴파일 클래스패스에 없고 sourcepath 도 보통 비어 있다. 그래서 방금 생성한 파일
     * (build/classes/java/main/...) 에서 위로 거슬러 올라가며 표준 레이아웃을 찾는다.
     * 앱이 빌드 스크립트에 경로를 적어주지 않아도 되도록 하려는 것이다.
     */
    private static java.io.File findCommittedLock(java.net.URI generatedUri) {
        if (generatedUri == null || !"file".equals(generatedUri.getScheme())) {
            return null;
        }
        java.io.File cursor = new java.io.File(generatedUri).getParentFile();
        for (int depth = 0; cursor != null && depth < 8; depth++, cursor = cursor.getParentFile()) {
            java.io.File candidate = new java.io.File(cursor, "src/main/resources/" + LOCK_FILE);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }
}
