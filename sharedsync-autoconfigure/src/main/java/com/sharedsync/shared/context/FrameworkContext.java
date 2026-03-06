package com.sharedsync.shared.context;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 프레임워크 전역에서 사용할 basePackage / appRootPackage 를 관리하는 컨텍스트.
 *
 * - 앱의 main 클래스 패키지를 자동으로 감지하여 basePackage에 저장
 * - Reflection 기반 자동 스캔이 필요한 모든 컴포넌트는 여기서 basePackage를 가져다 사용
 * - 한 번 초기화되면 static 지역에 저장되어 어디서든 접근 가능
 */
@Component
public class FrameworkContext implements ApplicationContextAware {

    private static String basePackage = "com";   // fallback 기본값
    private static boolean initialized = false;  // 중복 초기화 방지

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {

        // 이미 초기화되었다면 다시 할 필요 없음
        if (initialized) return;

        try {
            // 1. @SpringBootApplication 어노테이션이 붙은 빈을 찾아 패키지 경로 추출
            String[] beanNames = applicationContext.getBeanNamesForAnnotation(org.springframework.boot.autoconfigure.SpringBootApplication.class);
            if (beanNames.length > 0) {
                Object bootApp = applicationContext.getBean(beanNames[0]);
                Package bootPackage = bootApp.getClass().getPackage();
                if (bootPackage != null) {
                    basePackage = bootPackage.getName();
                }
            } else {
                // 2. fallback: sun.java.command (테스트 환경 등에서 유효할 수 있음)
                String mainClassName = applicationContext.getEnvironment()
                        .getProperty("sun.java.command");

                if (mainClassName != null) {
                    // "MainClass arg1 arg2 ..." 형식일 수 있으니 분리
                    mainClassName = mainClassName.split(" ")[0];
                    Class<?> mainClass = Class.forName(mainClassName);

                    Package mainPackage = mainClass.getPackage();
                    if (mainPackage != null) {
                        basePackage = mainPackage.getName();
                    }
                }
            }
        } catch (Exception ignored) {
            // 실패하면 fallback "com"
        }

        initialized = true;
    }

    /** 앱의 루트 패키지 (예: com.example.planmate) */
    public static String getBasePackage() {
        return basePackage;
    }

    /** 프레임워크가 basePackage 초기화를 완료했는지 여부 */
    public static boolean isInitialized() {
        return initialized;
    }
}
