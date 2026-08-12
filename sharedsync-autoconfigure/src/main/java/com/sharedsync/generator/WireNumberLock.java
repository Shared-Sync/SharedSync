package com.sharedsync.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import com.sharedsync.generator.Generator.CacheInformation;

/**
 * 필드 번호 부여와 잠금.
 *
 * proto 필드 번호는 **바이트에 실려 나가는 유일한 식별자**다. 같은 필드가 계속 같은 번호를 갖지
 * 않으면 클라이언트는 같은 바이트를 다른 필드로 읽는다. 값이 뒤섞일 뿐 파싱은 성공하므로 예외도
 * 나지 않는다.
 *
 * <h2>번호는 잠금이 정한다</h2>
 * 예전에는 번호를 엔티티의 선언 순서로만 매기고(1부터 세기), 잠금 파일은 그 결과를 <b>검증만</b>
 * 했다. 그래서 엔티티에서 필드를 하나 지우면 뒤 번호가 전부 한 칸씩 당겨지고, 잠금은 그걸 에러로
 * 막을 뿐 해결책을 주지 못했다 — 앱이 할 수 있는 일이 "잠금을 다시 만든다"(=번호 재사용)밖에 없다면
 * 그 잠금은 사고를 늦출 뿐이다.
 *
 * 지금은 잠금이 번호의 <b>출처</b>다:
 * <ul>
 *   <li>잠금에 있는 필드는 그 번호를 그대로 쓴다. 선언 순서를 바꿔도 번호는 안 움직인다.</li>
 *   <li>새 필드는 그 엔티티가 <b>지금까지 쓴 적 있는 가장 큰 번호 + 1</b> 을 받는다.</li>
 *   <li>지워진 필드의 번호는 잠금에 남아 <b>영구히 예약</b>된다. 다시 쓰이지 않는다.</li>
 * </ul>
 *
 * 그래서 필드를 지우는 것도, 중간에 끼우는 것도 더 이상 wire 를 깨지 않는다. 잠금이 없으면 예전처럼
 * 선언 순서로 매긴다(첫 빌드).
 *
 * 형식은 한 줄에 하나: {@code Entity.field=번호}. JSON 을 쓰지 않는 이유는 APT 가 파서를 끌어다 쓰지
 * 않게 하려는 것이다(애노테이션 프로세서의 의존성은 소비 앱의 컴파일 클래스패스에 얹힌다).
 */
final class WireNumberLock {

    static final String LOCK_FILE = "sharedsync-wire.lock";

    private WireNumberLock() {
    }

    /**
     * 잠금 파일 자리를 먼저 잡는다.
     *
     * 내용을 쓰기 전에 URI 가 필요하다 — 앱이 커밋해둔 잠금을 찾는 기준점이고, 그 잠금을 읽어야
     * 번호를 정할 수 있기 때문이다. {@link javax.annotation.processing.Filer} 는 같은 이름의 리소스를
     * 한 번만 만들 수 있으므로, 만들어만 두고 쓰기는 나중에 한다.
     *
     * @return 실패하면 null. 이 경우 번호는 선언 순서로 매겨진다.
     */
    static FileObject open(ProcessingEnvironment env) {
        try {
            return env.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", LOCK_FILE);
        } catch (IOException e) {
            env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[SharedSync] " + LOCK_FILE + " 생성 실패: " + e.getMessage());
            return null;
        }
    }

    /** 앱이 커밋해둔 잠금. 없으면 null (첫 빌드). */
    static Map<String, Integer> committed(ProcessingEnvironment env, FileObject lockFile) {
        if (lockFile == null) {
            return null;
        }
        Map<String, Integer> locked = readLock(lockFile.toUri());
        if (locked == null) {
            env.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[SharedSync] " + LOCK_FILE + " 이 없어 필드 번호를 선언 순서로 매긴다. "
                            + "생성된 파일을 src/main/resources/ 에 복사해 커밋하면 이후로는 그 번호가 "
                            + "고정되고, 필드를 지워도 번호가 밀리지 않는다.");
        }
        return locked;
    }

    /**
     * 이번 컴파일에서 각 필드가 쓸 번호. {@code Entity.field -> number}
     *
     * @param locked 커밋된 잠금. null 이면 선언 순서로 매긴다.
     */
    static Map<String, Integer> assign(List<CacheInformation> cacheInfoList, Map<String, Integer> locked) {
        Map<String, List<String>> fieldsByEntity = new LinkedHashMap<>();
        for (CacheInformation info : cacheInfoList) {
            List<String> names = new ArrayList<>();
            for (WireField field : WireFieldResolver.resolve(info)) {
                names.add(field.getProtoName());
            }
            fieldsByEntity.put(info.getEntityName(), names);
        }
        return assignNumbers(fieldsByEntity, locked);
    }

    /**
     * 번호 부여 규칙 그 자체. 애노테이션 처리 환경 없이 테스트할 수 있도록 분리해 둔다 —
     * 이 규칙이 틀리면 조용히 데이터가 섞이므로, 검증이 실제 빌드에만 의존해서는 안 된다.
     *
     * @param fieldsByEntity 엔티티 이름 -> proto 필드 이름(선언 순서)
     * @param locked         커밋된 잠금. null 이면 선언 순서로 매긴다.
     */
    static Map<String, Integer> assignNumbers(Map<String, List<String>> fieldsByEntity,
                                              Map<String, Integer> locked) {
        Map<String, Integer> numbers = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entity : fieldsByEntity.entrySet()) {
            // 이 엔티티가 지금까지 쓴 적 있는 번호. 지워진 필드의 것도 포함한다 — 그래야 재사용되지 않는다.
            TreeSet<Integer> used = new TreeSet<>(entityNumbers(locked, entity.getKey()));
            int sequential = 1;

            for (String fieldName : entity.getValue()) {
                String key = entity.getKey() + "." + fieldName;
                Integer pinned = locked == null ? null : locked.get(key);

                int number;
                if (pinned != null) {
                    number = pinned;
                } else if (locked == null) {
                    // 첫 빌드. 선언 순서 그대로.
                    number = sequential++;
                } else {
                    number = used.isEmpty() ? 1 : used.last() + 1;
                }

                numbers.put(key, number);
                used.add(number);
            }
        }
        return numbers;
    }

    /**
     * 더 이상 존재하지 않는 필드가 붙잡고 있는 번호. {@code Entity -> [번호...]}
     *
     * .proto 에 {@code reserved} 로 적어 내보낸다. 잠금은 이 저장소의 빌드만 지켜주지만, reserved 는
     * 스키마를 받아 쓰는 쪽(클라이언트 코드 생성, buf lint)까지 따라간다.
     */
    static Map<String, List<Integer>> reserved(Map<String, Integer> locked, Map<String, Integer> assigned) {
        Map<String, List<Integer>> byEntity = new LinkedHashMap<>();
        if (locked == null) {
            return byEntity;
        }
        for (Map.Entry<String, Integer> entry : locked.entrySet()) {
            if (assigned.containsKey(entry.getKey())) {
                continue;
            }
            int dot = entry.getKey().lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            byEntity.computeIfAbsent(entry.getKey().substring(0, dot), e -> new ArrayList<>())
                    .add(entry.getValue());
        }
        byEntity.values().forEach(java.util.Collections::sort);
        return byEntity;
    }

    /**
     * 잠금과 어긋나는지 확인한다.
     *
     * {@link #assign} 이 잠긴 번호를 그대로 쓰므로 정상 경로에서는 어긋날 수 없다. 잠금 파일을 손으로
     * 고쳐 같은 번호가 두 필드에 걸린 경우를 잡기 위한 최후의 그물이다 — 그건 조용히 데이터를 섞는다.
     */
    static void verify(Map<String, Integer> assigned, ProcessingEnvironment env) {
        Map<String, String> seen = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : assigned.entrySet()) {
            int dot = entry.getKey().lastIndexOf('.');
            String slot = entry.getKey().substring(0, Math.max(dot, 0)) + "#" + entry.getValue();
            String previous = seen.put(slot, entry.getKey());
            if (previous != null) {
                problems.add(previous + " 와 " + entry.getKey() + " 가 같은 번호 " + entry.getValue()
                        + " 를 쓴다. " + LOCK_FILE + " 이 손으로 수정되었을 가능성이 높다 —"
                        + " 한 슬롯에 두 필드가 걸리면 클라이언트가 값을 뒤섞어 읽는다.");
            }
        }

        for (String problem : problems) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR, "[SharedSync] wire 번호 충돌: " + problem);
        }
    }

    /**
     * 최신 상태의 잠금을 기록한다. 앱은 이걸 복사해 커밋한다.
     *
     * **지워진 필드의 줄도 그대로 남긴다.** 그게 번호를 붙잡아 두는 유일한 수단이다 — 지우면 다음
     * 빌드에서 그 번호가 새 필드에 재사용된다.
     */
    static void write(FileObject lockFile, Map<String, Integer> assigned, Map<String, Integer> locked,
                      ProcessingEnvironment env) {
        if (lockFile == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# SharedSync wire 필드 번호 잠금. 생성물이지만 커밋 대상이다.\n");
        sb.append("# src/main/resources/").append(LOCK_FILE).append(" 로 복사해두면 이후 빌드는\n");
        sb.append("# 여기 적힌 번호를 그대로 쓴다. 필드를 지우거나 순서를 바꿔도 번호가 밀리지 않는다.\n");
        for (Map.Entry<String, Integer> entry : assigned.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        Set<String> retired = new LinkedHashSet<>();
        if (locked != null) {
            for (Map.Entry<String, Integer> entry : locked.entrySet()) {
                if (!assigned.containsKey(entry.getKey())) {
                    retired.add(entry.getKey() + "=" + entry.getValue());
                }
            }
        }
        if (!retired.isEmpty()) {
            sb.append("\n# 아래는 엔티티에서 제거된 필드다. 번호를 영구히 붙잡아 두려고 남긴다 —\n");
            sb.append("# 이 줄을 지우면 다음 빌드에서 그 번호가 새 필드에 재사용되고, 구 클라이언트가\n");
            sb.append("# 보낸 값이 엉뚱한 필드로 읽힌다.\n");
            retired.forEach(line -> sb.append(line).append('\n'));
        }

        try (Writer writer = lockFile.openWriter()) {
            writer.write(sb.toString());
        } catch (IOException e) {
            env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[SharedSync] " + LOCK_FILE + " 기록 실패: " + e.getMessage());
        }
    }

    private static List<Integer> entityNumbers(Map<String, Integer> locked, String entity) {
        List<Integer> numbers = new ArrayList<>();
        if (locked == null) {
            return numbers;
        }
        String prefix = entity + ".";
        for (Map.Entry<String, Integer> entry : locked.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                numbers.add(entry.getValue());
            }
        }
        return numbers;
    }

    /** 앱이 커밋해둔 잠금 파일을 읽는다. 없으면 null. */
    private static Map<String, Integer> readLock(java.net.URI generatedUri) {
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
     * 리소스는 컴파일 클래스패스에 없고 sourcepath 도 보통 비어 있다. 그래서 생성 파일
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
