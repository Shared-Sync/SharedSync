package com.sharedsync.shared.repository.itest;

import java.util.UUID;

import com.sharedsync.shared.repository.AutoCacheRepository;

/** 통합 테스트 전용 캐시 리포지토리 (생성 서브클래스와 동일하게 빈 본문). */
public class WidgetRepository extends AutoCacheRepository<Widget, UUID, WidgetDto> {
}
