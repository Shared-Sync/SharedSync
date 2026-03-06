package com.sharedsync.shared.presence.core;

import com.sharedsync.shared.context.FrameworkContext;
import jakarta.annotation.PostConstruct;
import org.reflections.Reflections;
import org.springframework.stereotype.Component;

import com.sharedsync.shared.presence.annotation.PresenceRoot;

import java.util.Set;

@Component
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

        rootType = roots.iterator().next();
        PresenceRoot ann = rootType.getAnnotation(PresenceRoot.class);
        channelName = ann.channel();
    }

    public String getChannel() { return channelName; }
    public Class<?> getRootType() { return rootType; }
}
