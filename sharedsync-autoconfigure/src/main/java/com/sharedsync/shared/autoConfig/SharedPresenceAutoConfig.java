package com.sharedsync.shared.autoConfig;

import com.sharedsync.shared.presence.core.DummyUserProvider;
import com.sharedsync.shared.presence.core.SharedPresenceFacade;
import com.sharedsync.shared.presence.core.UserProvider;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;
import com.sharedsync.shared.storage.PresenceStorage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(SharedSyncPresenceProperties.class)
public class SharedPresenceAutoConfig {

    @Bean
    public SharedPresenceFacade sharedPresenceFacade(PresenceStorage presenceStorage) {
        return new SharedPresenceFacade(presenceStorage);
    }

    /**
     * presence.enabled=false 이고,
     * 앱 쪽에서 UserProvider 구현을 제공하지 않았을 때만
     * 더미 UserProvider를 자동 등록.
     */
    @Bean
    @ConditionalOnMissingBean(UserProvider.class)
    @ConditionalOnProperty(
            prefix = "sharedsync.presence",
            name = "enabled",
            havingValue = "false"
    )
    public UserProvider dummyUserProvider() {
        return new DummyUserProvider();
    }
}
