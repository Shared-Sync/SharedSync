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

    /**
     * auth.enabled=false 일 때의 폴백 resolver.
     *
     * @ConditionalOnMissingBean 이 없으면, 자체 resolver 를 가진 앱에서 빈이 두 개가 되어
     * 기동이 깨진다("required a single bean, but 2 were found"). 데모 모드를 켜는 순간에만
     * 드러나므로 아무도 밟지 않고 남아 있었다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sharedsync.auth", name = "enabled", havingValue = "false")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(AuthenticationTokenResolver.class)
    public AuthenticationTokenResolver dummyTokenResolver() {
        return new DummyAuthenticationTokenResolver();
    }

    // auth.enabled=true 일 때 실제 구현체는 앱에서 제공해야 함
}
