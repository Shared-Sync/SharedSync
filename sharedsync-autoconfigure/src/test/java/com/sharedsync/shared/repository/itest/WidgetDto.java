package com.sharedsync.shared.repository.itest;

import java.util.UUID;

import com.sharedsync.shared.annotation.Cache;
import com.sharedsync.shared.annotation.CacheId;
import com.sharedsync.shared.annotation.EntityConverter;
import com.sharedsync.shared.dto.CacheDto;

/** 통합 테스트 전용 DTO. {@link Widget} 와 1:1 매핑. */
@Cache(keyType = "widget")
public class WidgetDto extends CacheDto<UUID> {

    @CacheId
    private UUID id;

    private String name;

    @Override
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** DTO → Entity (관계 인자 없음 → buildEntityConverterParameters 는 빈 배열). */
    @EntityConverter
    public Widget toEntity() {
        return new Widget(id, name);
    }

    /** Entity → DTO (convertToDto 가 리플렉션으로 호출하는 정적 메서드). */
    public static WidgetDto fromEntity(Widget entity) {
        WidgetDto dto = new WidgetDto();
        dto.id = entity.getId();
        dto.name = entity.getName();
        return dto;
    }
}
