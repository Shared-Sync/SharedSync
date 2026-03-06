package com.sharedsync.shared.config;

import java.time.Duration;
import java.util.Set;

import org.reflections.Reflections;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sharedsync.shared.annotation.Cache;
import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.repository.CacheStore;
import com.sharedsync.shared.repository.RedisCacheStore;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;

/**
 * Redis 캐시 설정.
 * sharedsync.cache.type=redis 일 때만 활성화됩니다.
 * 기본값은 인메모리 캐시를 사용합니다.
 */
@EnableCaching
@Configuration
@ConditionalOnProperty(name = "sharedsync.cache.type", havingValue = "redis")
public class RedisConfig implements ApplicationContextAware {

    private String basePackage = "com"; // fallback 기본값

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        String mainClassName = applicationContext.getEnvironment().getProperty("sun.java.command");
        if (mainClassName != null) {
            try {
                Class<?> mainClass = Class.forName(mainClassName.split(" ")[0]);
                Package mainPackage = mainClass.getPackage();
                if (mainPackage != null) {
                    basePackage = mainPackage.getName();
                }
            } catch (Exception e) {
                basePackage = "com";
            }
        }
    }

    @Bean
    public RedisTemplate<String, Integer> refreshRedisTemplate(
            @Qualifier("sharedSyncRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Integer> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    @Bean(name = "sharedSyncRedisConnectionFactory")
    public RedisConnectionFactory sharedSyncRedisConnectionFactory(
            @Value("${sharedsync.redis.host:${spring.data.redis.host:localhost}}") String host,
            @Value("${sharedsync.redis.replica-host:${spring.data.redis.replica-host:}}") String replicaHost,
            @Value("${sharedsync.redis.port:${spring.data.redis.port:6379}}") String portStr,
            @Value("${sharedsync.redis.password:${spring.data.redis.password:}}") String password) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .readFrom(ReadFrom.REPLICA_PREFERRED)
                .clientOptions(ClientOptions.builder()
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .autoReconnect(true)
                        .build())
                .commandTimeout(Duration.ofSeconds(5))
                .build();

        int port = 6379;
        try {
            if (portStr != null && !portStr.startsWith("tcp://")) {
                port = Integer.parseInt(portStr);
            }
        } catch (NumberFormatException e) {
            port = 6379;
        }

        // Replica 설정이 있는 경우 Static Master/Replica 구성 사용 (읽기 분산)
        if (replicaHost != null && !replicaHost.isEmpty()) {
            RedisStaticMasterReplicaConfiguration staticConfig = new RedisStaticMasterReplicaConfiguration(host, port);
            staticConfig.addNode(replicaHost, port);

            if (password != null && !password.isEmpty()) {
                staticConfig.setPassword(RedisPassword.of(password));
            }

            LettuceConnectionFactory factory = new LettuceConnectionFactory(staticConfig, clientConfig);
            factory.afterPropertiesSet();
            return factory;
        }

        // Standalone 구성 (기존 방식)
        RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isEmpty()) {
            standaloneConfig.setPassword(RedisPassword.of(password));
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    /**
     * Pub/Sub 전용 RedisConnectionFactory.
     * Master/Replica 설정이 활성화된 경우에도 Pub/Sub은 Master와 직접 연결되어야 합니다.
     * (Lettuce의 MasterReplica 설정은 Pub/Sub subscription을 지원하지 않음)
     */
    @Bean(name = "pubSubConnectionFactory")
    public RedisConnectionFactory pubSubConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") String portStr,
            @Value("${spring.data.redis.password:}") String password) {
        int port = 6379;
        try {
            if (portStr != null && !portStr.startsWith("tcp://")) {
                port = Integer.parseInt(portStr);
            }
        } catch (NumberFormatException e) {
            port = 6379;
        }

        // Pub/Sub은 항상 Standalone 구성을 사용하여 Master와 직접 통신하도록 합니다.
        RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isEmpty()) {
            standaloneConfig.setPassword(RedisPassword.of(password));
        }

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(5))
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean(name = "sharedSyncRedisMessageListenerContainer")
    public org.springframework.data.redis.listener.RedisMessageListenerContainer sharedSyncRedisMessageListenerContainer(
            @Qualifier("pubSubConnectionFactory") RedisConnectionFactory connectionFactory) {
        org.springframework.data.redis.listener.RedisMessageListenerContainer container = new org.springframework.data.redis.listener.RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    @Bean
    public GenericJackson2JsonRedisSerializer redisValueSerializer() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }

    @Bean(name = "presenceRedis")
    public RedisTemplate<String, Object> presenceRedis(
            @Qualifier("sharedSyncRedisConnectionFactory") RedisConnectionFactory connectionFactory,
            GenericJackson2JsonRedisSerializer serializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        configureSerializers(template, serializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Redis 기반 globalCacheStore 빈 등록
     * AutoCacheRepository에서 getCacheStore()가 이 빈을 우선적으로 사용합니다.
     */
    @Bean(name = "globalCacheStore")
    @SuppressWarnings("rawtypes")
    public CacheStore redisCacheStore(
            @Qualifier("sharedSyncRedisConnectionFactory") RedisConnectionFactory connectionFactory,
            GenericJackson2JsonRedisSerializer serializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        configureSerializers(template, serializer);
        template.afterPropertiesSet();
        System.out.println("[SharedSync] Using Redis cache store");
        return new RedisCacheStore<>(template);
    }

    @Bean
    public static BeanDefinitionRegistryPostProcessor redisBeanDefinitionRegistryPostProcessor() {
        return new RedisBeanDefinitionRegistryPostProcessor();
    }

    private static class RedisBeanDefinitionRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {
        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            // Only scan sharedsync.dto for @Cache-annotated DTOs
            Reflections reflections = new Reflections("sharedsync.dto");
            Set<Class<?>> cacheDtos = reflections.getTypesAnnotatedWith(Cache.class);

            for (Class<?> dtoClass : cacheDtos) {
                if (!CacheDto.class.isAssignableFrom(dtoClass)) {
                    continue;
                }

                String beanName = resolveBeanName(dtoClass);
                if (registry.containsBeanDefinition(beanName)) {
                    continue;
                }

                GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
                beanDefinition.setBeanClass(DynamicRedisTemplateFactoryBean.class);
                beanDefinition.getConstructorArgumentValues().addGenericArgumentValue(dtoClass);
                beanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);
                registry.registerBeanDefinition(beanName, beanDefinition);
            }
        }

        private String resolveBeanName(Class<?> dtoClass) {
            String simpleName = dtoClass.getSimpleName();
            if (simpleName.endsWith("Dto")) {
                simpleName = simpleName.substring(0, simpleName.length() - 3);
            }
            return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1) + "Redis";
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            // no-op
        }
    }

    private static void configureSerializers(RedisTemplate<String, ?> template) {
        configureSerializers(template, null);
    }

    private static void configureSerializers(
            RedisTemplate<String, ?> template,
            GenericJackson2JsonRedisSerializer customSerializer) {
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer serializer = customSerializer != null ? customSerializer
                : new GenericJackson2JsonRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(serializer);
        template.setDefaultSerializer(serializer);
    }

    public static class DynamicRedisTemplateFactoryBean<T>
            implements FactoryBean<RedisTemplate<String, T>> {

        @SuppressWarnings("unused")
        private final Class<T> valueType;

        private RedisConnectionFactory connectionFactory;
        private GenericJackson2JsonRedisSerializer serializer;

        public DynamicRedisTemplateFactoryBean(Class<T> valueType) {
            this.valueType = valueType;
        }

        @Autowired
        public void setConnectionFactory(
                @Qualifier("sharedSyncRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
            this.connectionFactory = connectionFactory;
        }

        @Autowired
        public void setSerializer(GenericJackson2JsonRedisSerializer serializer) {
            this.serializer = serializer;
        }

        @Override
        public RedisTemplate<String, T> getObject() {
            RedisTemplate<String, T> template = new RedisTemplate<>();
            template.setConnectionFactory(connectionFactory);
            configureSerializers(template, serializer);
            template.afterPropertiesSet();
            return template;
        }

        @Override
        public Class<?> getObjectType() {
            return RedisTemplate.class;
        }

        @Override
        public boolean isSingleton() {
            return true;
        }
    }
}
