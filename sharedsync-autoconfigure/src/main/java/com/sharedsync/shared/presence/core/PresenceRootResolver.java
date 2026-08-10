package com.sharedsync.shared.presence.core;

import com.sharedsync.shared.context.FrameworkContext;
import jakarta.annotation.PostConstruct;
import org.reflections.Reflections;
import org.springframework.stereotype.Component;

import com.sharedsync.shared.presence.annotation.PresenceRoot;

import java.util.Set;

public class PresenceRootResolver {

    private String channelName;
    private Class<?> rootType;

    @PostConstruct
    public void init() {
        String basePackage = FrameworkContext.getBasePackage();
        Reflections reflections = new Reflections(basePackage);


        Set<Class<?>> roots = reflections.getTypesAnnotatedWith(PresenceRoot.class);

        if (roots.isEmpty())
            throw new IllegalStateException("No @PresenceRoot found");
        if (roots.size() > 1) {
            // 예전에는 iterator().next() 로 아무거나 골랐다. 어느 것이 뽑혔는지에 따라 프레즌스
            // 채널 이름이 달라지므로, 배포마다 다른 목적지를 쓰게 될 수도 있었다.
            throw new IllegalStateException("@PresenceRoot 는 하나여야 한다. 발견된 것: "
                    + roots.stream().map(Class::getName).sorted().toList());
        }

        rootType = roots.iterator().next();
        PresenceRoot ann = rootType.getAnnotation(PresenceRoot.class);
        channelName = ann.channel();
    }

    public String getChannel() { return channelName; }
    public Class<?> getRootType() { return rootType; }
}
