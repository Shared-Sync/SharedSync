package com.sharedsync.shared.config;

import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 임베디드 서블릿 컨테이너가 실제로 뜨는 컨텍스트인지 판별한다.
 *
 * {@code @ConditionalOnBean(ServletWebServerFactory.class)} 로는 갈라지지 않는다 — MockMvc
 * 컨텍스트(@SpringBootTest 기본값, @WebMvcTest)에도 팩토리 **빈 정의**는 존재하고 쓰이지만 않는다.
 * 컨텍스트 타입이 유일하게 정확한 신호다.
 *
 * 이 조건이 필요한 이유: ServletServerContainerFactoryBean 은 ServletContext 에서
 * ServerContainer 속성을 읽는데, mock 환경에는 그 속성이 없어 빈 생성이 실패하고
 * **WebSocket 과 무관한 컨트롤러 테스트까지 전부 죽는다.**
 */
class EmbeddedServletContainerCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return context.getResourceLoader() instanceof ServletWebServerApplicationContext;
    }
}
