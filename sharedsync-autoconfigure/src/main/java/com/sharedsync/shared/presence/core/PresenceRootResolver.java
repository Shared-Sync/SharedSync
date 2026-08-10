package com.sharedsync.shared.presence.core;

import jakarta.annotation.PostConstruct;

import com.sharedsync.shared.presence.annotation.PresenceRoot;

/**
 * 프레즌스 루트 엔티티와 채널 이름.
 *
 * 예전에는 기동할 때마다 org.reflections 로 앱 패키지를 스캔했다. 스캔 범위를 추측해야 했고
 * (실패하면 "com" 전체), @PresenceRoot 이 여러 개면 iterator().next() 로 아무거나 골랐다.
 * 지금은 애노테이션 프로세서가 컴파일 시점에 적어둔 이름을 읽는다.
 */
public class PresenceRootResolver {

    private static final String METADATA_CLASS = "sharedsync.presence.PresenceMetadata";

    private String channelName;
    private Class<?> rootType;

    @PostConstruct
    public void init() {
        String rootClassName = metadataField("ROOT_CLASS");
        if (rootClassName == null) {
            throw new IllegalStateException(
                    "@PresenceRoot 이 붙은 엔티티가 없다. 프레즌스를 쓰려면 루트 엔티티에 "
                            + "@PresenceRoot(channel = \"...\") 를 붙일 것.");
        }

        try {
            rootType = Class.forName(rootClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("@PresenceRoot 클래스를 로드할 수 없다: " + rootClassName, e);
        }

        PresenceRoot annotation = rootType.getAnnotation(PresenceRoot.class);
        if (annotation == null) {
            throw new IllegalStateException(rootClassName + " 에 @PresenceRoot 이 없다. "
                    + "생성 메타데이터가 오래된 것이니 클린 빌드할 것.");
        }
        channelName = annotation.channel();
    }

    /**
     * 생성물에서 필드를 읽는다. 컴파일 의존이 아니라 리플렉션인 이유: PresenceMetadata 는 소비
     * 애플리케이션의 컴파일 시점에 만들어지므로 프레임워크 자신에게는 존재하지 않는다.
     */
    static String metadataField(String fieldName) {
        try {
            Class<?> metadata = Class.forName(METADATA_CLASS);
            return (String) metadata.getField(fieldName).get(null);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    METADATA_CLASS + " 가 없다. sharedsync-autoconfigure 를 annotationProcessor 로 "
                            + "걸었는지 확인할 것.", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("프레즌스 메타데이터를 읽을 수 없다", e);
        }
    }

    public String getChannel() { return channelName; }
    public Class<?> getRootType() { return rootType; }
}
