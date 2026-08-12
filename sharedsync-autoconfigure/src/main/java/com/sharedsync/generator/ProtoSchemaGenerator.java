package com.sharedsync.generator;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import com.sharedsync.generator.Generator.CacheInformation;

/**
 * @CacheEntity 목록에서 wire 스키마(.proto)와 그 해시를 생성한다.
 *
 * protoc 를 쓰지 않는다. SharedSync 는 JitPack 에서 소스 빌드되므로 빌드 시점에 protoc 바이너리를
 * 내려받는 구성은 깨지기 쉽다. 여기서는 .proto **텍스트**만 만들고, 실제 인코딩은
 * ProtoCodecGenerator 가 만든 코덱이 protobuf-java 런타임으로 수행한다.
 *
 * 산출물:
 *  1. &lt;proto.file&gt; — .proto 텍스트. 클라이언트가 buf generate 로 TS 를 만들 소스이자
 *     Backend-v2 가 /internal/sync-schema.proto 로 서빙할 리소스.
 *  2. sharedsync.proto.SyncSchema — 위 텍스트와 그 SHA-256 해시를 담은 자바 상수 클래스.
 *
 * 해시를 디스크립터가 아니라 **.proto 텍스트**에서 뽑는 이유: 서버는 손으로 조립한
 * FileDescriptorProto 를, 클라이언트는 buf/protoc 가 만든 디스크립터를 갖게 되는데 이 둘은 같은
 * 스키마의 서로 다른 인코딩이다(protoc 는 proto3 optional 마다 synthetic oneof 와 json_name 을
 * 넣는다). 텍스트는 서버가 내려준 바로 그 바이트를 클라가 받아 쓰므로 양쪽이 같은 값을 해시한다.
 */
public class ProtoSchemaGenerator {

    public static final String OPTION_PACKAGE = "sharedsync.proto.package";
    public static final String OPTION_FILE = "sharedsync.proto.file";

    private static final String DEFAULT_PACKAGE = "sharedsync.wire.v1";
    private static final String DEFAULT_FILE = "sharedsync/wire/v1/sync.proto";

    public static void generate(List<CacheInformation> cacheInfoList, ProcessingEnvironment env) {
        if (cacheInfoList.isEmpty()) {
            return;
        }

        // 앱이 좌표를 지정하지 않으면 엔티티 패키지에서 유도한다. 빌드 스크립트에 두 줄을 적어야
        // wire 스키마가 나오는 구조라면, 그건 프레임워크가 앱에 숙제를 미룬 것이다.
        String protoPackage = option(env, OPTION_PACKAGE, derivePackage(cacheInfoList));
        String protoFile = option(env, OPTION_FILE, protoPackage.replace('.', '/') + "/sync.proto");

        // 필드 번호를 **먼저** 정한다. 텍스트와 디스크립터가 각자 세면 언젠가 갈라지고, 갈라지면
        // 스키마에는 있는데 페이로드에는 없는 필드가 생겨 조용히 데이터가 사라진다.
        // 번호의 출처는 커밋된 잠금이다 — 잠긴 필드는 그 번호를 그대로 쓰고, 지워진 필드의 번호는
        // 예약되어 재사용되지 않는다 (§WireNumberLock).
        FileObject lockFile = WireNumberLock.open(env);
        Map<String, Integer> locked = WireNumberLock.committed(env, lockFile);
        Map<String, Integer> numbers = WireNumberLock.assign(cacheInfoList, locked);
        Map<String, List<Integer>> reserved = WireNumberLock.reserved(locked, numbers);
        WireNumberLock.verify(numbers, env);
        WireNumberLock.write(lockFile, numbers, locked, env);

        ProtoTypeMapper mapper = new ProtoTypeMapper(env);
        String protoText = buildProtoText(cacheInfoList, protoPackage, mapper, numbers, reserved);
        String schemaHash = sha256Prefix(protoText.getBytes(StandardCharsets.UTF_8));

        // 텍스트와 같은 필드 모델·같은 번호에서 디스크립터를 조립한다.
        String descriptorBase64 = java.util.Base64.getEncoder().encodeToString(
                ProtoDescriptorBuilder.build(cacheInfoList, protoPackage, protoFile, mapper, numbers, reserved)
                        .toByteArray());

        writeProtoResource(env, protoFile, protoText);
        writeSchemaClass(env, protoPackage, protoFile, protoText, schemaHash, descriptorBase64);

        env.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "[SharedSync] wire schema generated: " + protoFile + " (hash=" + schemaHash + ")");
    }

    /**
     * 엔티티들의 공통 패키지에서 proto package 를 만든다.
     * 예: com.planmate.domain.plan.entity.Plan / ...timetable.entity.TimeTable -> planmate.sync.v1
     *
     * buf lint STANDARD 는 package 가 버전으로 끝나고 디렉터리 경로가 그와 일치할 것을 요구하므로,
     * 파일 경로도 이 값에서 함께 만든다.
     */
    private static String derivePackage(List<CacheInformation> cacheInfoList) {
        List<String> segments = null;
        for (CacheInformation info : cacheInfoList) {
            String path = info.getEntityPath();
            if (path == null || path.isBlank()) {
                continue;
            }
            List<String> parts = new ArrayList<>(List.of(path.split("\\.")));
            parts.remove(parts.size() - 1); // 클래스명 제거
            if (segments == null) {
                segments = parts;
                continue;
            }
            int common = 0;
            while (common < segments.size() && common < parts.size()
                    && segments.get(common).equals(parts.get(common))) {
                common++;
            }
            segments = new ArrayList<>(segments.subList(0, common));
        }

        if (segments == null || segments.isEmpty()) {
            return DEFAULT_PACKAGE;
        }
        // com.planmate -> planmate. 앞의 com/org/io 같은 관용 접두사는 스키마 이름에 의미가 없다.
        String name = segments.get(segments.size() - 1);
        if (segments.size() > 1 && List.of("com", "org", "io", "net", "kr", "co").contains(segments.get(0))
                && segments.size() >= 2) {
            name = segments.get(1);
        }
        name = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        return name.isEmpty() ? DEFAULT_PACKAGE : name + ".sync.v1";
    }

    // ==========================================
    // .proto 텍스트
    // ==========================================
    private static String buildProtoText(List<CacheInformation> cacheInfoList, String protoPackage,
                                          ProtoTypeMapper mapper, Map<String, Integer> numbers,
                                          Map<String, List<Integer>> reserved) {
        StringBuilder messages = new StringBuilder();

        // 엔티티별 메시지 + 리스트 래퍼. mapper 를 먼저 돌려야 enum 이 수집된다.
        for (CacheInformation info : cacheInfoList) {
            messages.append(buildEntityMessage(info, mapper, numbers,
                    reserved.getOrDefault(info.getEntityName(), List.of())));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// Code generated by SharedSync annotation processor. DO NOT EDIT.\n");
        sb.append("//\n");
        sb.append("// 이 파일은 @CacheEntity 엔티티에서 자동 생성된다. 손으로 고치지 말고 엔티티를 고칠 것.\n");
        sb.append("// 필드 번호는 sharedsync-wire.lock 이 정한다. 잠긴 필드는 선언 순서를 바꿔도 번호가\n");
        sb.append("// 움직이지 않고, 제거된 필드의 번호는 reserved 로 남아 재사용되지 않는다.\n");
        sb.append("\n");
        sb.append("syntax = \"proto3\";\n\n");
        sb.append("package ").append(protoPackage).append(";\n\n");

        sb.append(buildActionEnum());

        // 수집된 자바 enum 들
        for (Map.Entry<String, String> entry : mapper.getEnumTypes().entrySet()) {
            sb.append(buildEnum(entry.getValue(), mapper.enumConstants(entry.getKey())));
        }

        sb.append(messages);
        sb.append(buildEnvelopes(cacheInfoList));

        return sb.toString();
    }

    private static String buildActionEnum() {
        return """
                // 편집 동작. undo/redo 는 별도 페이로드 없이 action 만 실려 온다.
                enum SyncAction {
                  SYNC_ACTION_UNSPECIFIED = 0;
                  SYNC_ACTION_CREATE = 1;
                  SYNC_ACTION_UPDATE = 2;
                  SYNC_ACTION_DELETE = 3;
                  SYNC_ACTION_UNDO = 4;
                  SYNC_ACTION_REDO = 5;
                }

                """;
    }

    private static String buildEnum(String protoEnumName, List<String> constants) {
        String prefix = ProtoTypeMapper.enumValuePrefix(protoEnumName);
        StringBuilder sb = new StringBuilder();
        sb.append("enum ").append(protoEnumName).append(" {\n");
        // buf lint STANDARD: 0 번은 반드시 <ENUM_NAME>_UNSPECIFIED
        sb.append("  ").append(prefix).append("_UNSPECIFIED = 0;\n");
        int number = 1;
        for (String constant : constants) {
            sb.append("  ").append(prefix).append("_").append(constant).append(" = ").append(number++).append(";\n");
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private static String buildEntityMessage(CacheInformation info, ProtoTypeMapper mapper,
                                            Map<String, Integer> numbers, List<Integer> reserved) {
        List<WireField> fields = WireFieldResolver.resolve(info);
        String messageName = info.getEntityName();

        StringBuilder sb = new StringBuilder();
        sb.append("message ").append(messageName).append(" {\n");

        // 제거된 필드의 번호. 스키마를 받아 쓰는 쪽에서도 재사용을 막아준다.
        if (!reserved.isEmpty()) {
            sb.append("  reserved ");
            for (int i = 0; i < reserved.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(reserved.get(i));
            }
            sb.append(";\n");
        }

        for (WireField field : fields) {
            String protoType = field.isRepeated()
                    ? mapper.toProtoType(field.getElementType())
                    : mapper.toProtoType(field.getJavaType());

            sb.append("  ");
            if (field.isRepeated()) {
                sb.append("repeated ");
            } else if (!field.isJavaPrimitive()) {
                // 참조 타입은 명시적 presence 를 준다. 미설정과 기본값을 구분하지 못하면
                // EntityDtoConverter 의 null-skip 부분 병합이 무너져 안 보낸 필드가 빈 값으로 덮인다.
                sb.append("optional ");
            }
            sb.append(protoType).append(" ").append(field.getProtoName())
                    .append(" = ").append(numbers.get(messageName + "." + field.getProtoName())).append(";\n");
        }

        sb.append("}\n\n");
        sb.append("message ").append(messageName).append("List {\n");
        sb.append("  repeated ").append(messageName).append(" items = 1;\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private static String buildEnvelopes(List<CacheInformation> cacheInfoList) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                // 클라이언트 -> 서버.
                // room 은 Join 으로 세션에 귀속되므로 매 프레임에 싣지 않는다.
                message ClientFrame {
                  oneof frame {
                    Join join = 1;
                    SyncRequest sync = 2;
                    Ping ping = 3;
                  }
                }

                message Join {
                  string room_id = 1;
                  // 클라이언트가 생성에 사용한 스키마의 해시. 서버와 다르면 거부된다.
                  string schema_hash = 2;
                }

                message Ping {}
                message Pong {}

                message Error {
                  string code = 1;
                  string message = 2;
                }

                """);

        sb.append("message SyncRequest {\n");
        sb.append("  string event_id = 1;\n");
        sb.append("  SyncAction action = 2;\n");
        sb.append(buildPayloadOneof(cacheInfoList));
        sb.append("}\n\n");

        sb.append("""
                // 서버 -> 클라이언트.
                message ServerFrame {
                  oneof frame {
                    Hello hello = 1;
                    SyncEvent sync = 2;
                    PresenceEvent presence = 3;
                    Pong pong = 4;
                    Error error = 5;
                  }
                }

                message Hello {
                  string schema_hash = 1;
                }

                // 프레즌스 브로드캐스트.
                // user_info 를 map 으로 두는 이유: 노출 필드가 @PresenceUser(fields=...) 로 앱마다
                // 달라서 프레임워크가 타입을 고정할 수 없다. 현행 JSON 페이로드도 Map<String,Object> 다.
                message PresenceUser {
                  string uid = 1;
                  map<string, string> user_info = 2;
                }

                message PresenceEvent {
                  SyncAction action = 1;
                  string uid = 2;
                  map<string, string> user_info = 3;
                  repeated PresenceUser users = 4;
                }

                """);

        sb.append("// 편집 브로드캐스트. undo/redo 도 같은 모양으로 오고 is_undo_redo 로만 구분된다.\n");
        sb.append("message SyncEvent {\n");
        sb.append("  string event_id = 1;\n");
        sb.append("  SyncAction action = 2;\n");
        sb.append("  bool is_undo_redo = 3;\n");
        sb.append(buildPayloadOneof(cacheInfoList));
        sb.append("}\n\n");

        return sb.toString();
    }

    /** 엔티티 문자열 switch 를 대체하는 oneof. 번호는 10 번부터 고정 필드와 겹치지 않게 둔다. */
    private static String buildPayloadOneof(List<CacheInformation> cacheInfoList) {
        StringBuilder sb = new StringBuilder();
        sb.append("  oneof payload {\n");
        int number = 10;
        for (CacheInformation info : cacheInfoList) {
            String messageName = info.getEntityName();
            sb.append("    ").append(messageName).append("List ")
                    .append(WireField.toSnakeCase(messageName)).append("s")
                    .append(" = ").append(number++).append(";\n");
        }
        sb.append("  }\n");
        return sb.toString();
    }

    // ==========================================
    // 산출물 기록
    // ==========================================
    private static void writeProtoResource(ProcessingEnvironment env, String protoFile, String protoText) {
        try {
            FileObject file = env.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", protoFile);
            try (Writer writer = file.openWriter()) {
                writer.write(protoText);
            }
        } catch (IOException e) {
            // 스키마가 없으면 클라이언트가 코드를 생성할 근거를 잃는다. 경고로 넘기면
            // 서버만 새 스키마를 갖고 클라는 옛 스키마로 붙어 해시 불일치가 배포 후에 드러난다.
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[SharedSync] .proto 파일 기록 실패: " + e.getMessage());
        }
    }

    private static void writeSchemaClass(ProcessingEnvironment env, String protoPackage, String protoFile,
                                          String protoText, String schemaHash, String descriptorBase64) {
        String source = "package sharedsync.proto;\n\n"
                + "/**\n"
                + " * SharedSync annotation processor 생성물. 손으로 고치지 말 것.\n"
                + " *\n"
                + " * SCHEMA_HASH 는 PROTO_TEXT 의 SHA-256 앞 8바이트다. 클라이언트가 내려받아 생성에 쓴\n"
                + " * .proto 바이트와 동일하므로, 연결 시점에 양쪽 해시를 비교해 스키마 스큐를 잡을 수 있다.\n"
                + " * (디스크립터 바이트로 해시하면 protoc 산출본과 인코딩이 달라 항상 불일치한다.)\n"
                + " *\n"
                + " * DESCRIPTOR_BASE64 는 PROTO_TEXT 와 같은 필드 모델에서 조립한 FileDescriptorProto 다.\n"
                + " * 런타임 코덱이 이걸로 Descriptors.FileDescriptor 를 만들어 DynamicMessage 인코딩에 쓴다.\n"
                + " */\n"
                + "public final class SyncSchema {\n\n"
                + "    private SyncSchema() {}\n\n"
                + "    public static final String PROTO_PACKAGE = \"" + protoPackage + "\";\n\n"
                + "    public static final String PROTO_FILE = \"" + protoFile + "\";\n\n"
                + "    public static final String SCHEMA_HASH = \"" + schemaHash + "\";\n\n"
                + "    public static final String DESCRIPTOR_BASE64 = \"" + descriptorBase64 + "\";\n\n"
                + "    public static final String PROTO_TEXT = " + toJavaStringLiteral(protoText) + ";\n"
                + "}\n";

        try {
            JavaFileObject file = env.getFiler().createSourceFile("sharedsync.proto.SyncSchema");
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException e) {
            // SyncSchema 가 없으면 protobuf 코덱이 런타임에야 실패한다(SyncDescriptors 가 이 클래스를
            // 리플렉션으로 찾는다). 컴파일 시점에 끝내는 편이 낫다.
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[SharedSync] SyncSchema 생성 실패: " + e.getMessage());
        }
    }

    /**
     * 긴 텍스트를 줄 단위로 이어붙인 자바 문자열 리터럴로 만든다 (상수 풀 64KB 제한 회피).
     *
     * text == String.join("\n", split("\n", -1)) 이므로 마지막 조각에는 개행을 붙이지 않는다.
     * 여기서 개행 하나가 더 붙으면 PROTO_TEXT 가 실제 .proto 파일과 달라져 스키마 해시가 어긋난다.
     */
    private static String toJavaStringLiteral(String text) {
        String[] lines = text.split("\n", -1);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String suffix = (i < lines.length - 1) ? "\\n" : "";
            parts.add("\"" + escape(lines[i]) + suffix + "\"");
        }
        return String.join("\n            + ", parts);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sha256Prefix(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 사용 불가", e);
        }
    }

    private static String option(ProcessingEnvironment env, String key, String fallback) {
        String value = env.getOptions().get(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
