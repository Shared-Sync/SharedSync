package com.sharedsync.shared.autoConfig;

import com.sharedsync.shared.auth.AuthenticationTokenResolver;
import com.sharedsync.shared.auth.resolver.DummyAuthenticationTokenResolver;
import com.sharedsync.shared.properties.SharedSyncAuthProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(SharedSyncAuthProperties.class)
public class SharedSyncAuthAutoConfig {

    // auth.enabled=false 일 때 Dummy resolver 활성화
    @Bean
    @ConditionalOnProperty(prefix = "sharedsync.auth", name = "enabled", havingValue = "false")
    public AuthenticationTokenResolver dummyTokenResolver() {
        return new DummyAuthenticationTokenResolver();
    }

    // auth.enabled=true 일 때 실제 구현체는 앱에서 제공해야 함
}
