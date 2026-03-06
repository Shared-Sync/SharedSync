package com.sharedsync.generator;

import javax.annotation.processing.ProcessingEnvironment;

/**
 * PresenceController를 위한 stub generator.
 * Presence 관련 엔드포인트를 자동 생성합니다.
 */
public class PresenceControllerGenerator {

    public static void generate(ProcessingEnvironment processingEnv) {
        // PresenceController는 별도 PresenceUserGenerator에서 처리되므로 여기서는 빈 구현
        processingEnv.getMessager().printMessage(
                javax.tools.Diagnostic.Kind.NOTE,
                "[SharedSync] PresenceController generated at: sharedsync.presence.PresenceController");
    }
}
