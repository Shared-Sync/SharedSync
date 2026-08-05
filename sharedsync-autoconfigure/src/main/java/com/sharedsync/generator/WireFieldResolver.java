package com.sharedsync.generator;

import java.util.ArrayList;
import java.util.List;

import com.sharedsync.generator.Generator.CacheInformation;
import com.sharedsync.generator.Generator.FieldInfo;
import com.sharedsync.generator.Generator.RelatedEntity;

/**
 * CacheInformation 에서 DTO 가 갖게 될 필드 목록을 순서대로 뽑는다.
 *
 * DtoGenerator.writeDtoFields() 와 **같은 규칙**을 따라야 한다. 그쪽이 실제 wire 에 나가는 DTO 를
 * 만들고 이쪽은 그 스키마를 만들기 때문이다. 규칙이 갈라지면 스키마와 실제 페이로드가 어긋난다.
 */
public final class WireFieldResolver {

    private WireFieldResolver() {
    }

    public static List<WireField> resolve(CacheInformation cacheInfo) {
        List<WireField> fields = new ArrayList<>();

        // 1) @CacheId 필드가 항상 첫 번째
        fields.add(new WireField(cacheInfo.getIdName(), cacheInfo.getIdType(), null));

        for (FieldInfo fieldInfo : cacheInfo.getEntityFields()) {
            if (fieldInfo.getName().equals(cacheInfo.getIdName()) || fieldInfo.isIgnored()) {
                continue;
            }

            RelatedEntity matched = cacheInfo.getRelatedEntities().stream()
                    .filter(re -> isSameEntity(fieldInfo, re))
                    .findFirst()
                    .orElse(null);

            if (matched != null && (fieldInfo.isManyToOne() || fieldInfo.isOneToOne())) {
                // 연관 엔티티는 FK ID 하나로 평탄화된다
                fields.add(new WireField(
                        matched.getCacheEntityIdName(),
                        Generator.denormalizeType(matched.getEntityIdType(), matched.getEntityIdOriginalType()),
                        null));

            } else if (matched != null && (fieldInfo.isOneToMany() || fieldInfo.isManyToMany())) {
                // 컬렉션 연관은 FK ID 목록으로 평탄화된다
                fields.add(new WireField(
                        matched.getCacheEntityIdName() + "s",
                        "java.util.Collection",
                        Generator.denormalizeType(matched.getEntityIdType(), matched.getEntityIdOriginalType())));

            } else {
                fields.add(new WireField(
                        fieldInfo.getName(),
                        Generator.denormalizeType(fieldInfo.getType(), fieldInfo.getOriginalType()),
                        null));
            }
        }

        return fields;
    }

    /** DtoGenerator.isSameEntity 와 동일한 판정 */
    private static boolean isSameEntity(FieldInfo fieldInfo, RelatedEntity related) {
        String fieldType = fieldInfo.getType();
        String simple = Generator.removePath(related.getEntityPath());

        return fieldType.endsWith("." + simple)
                || fieldType.equals(simple)
                || fieldType.endsWith("$" + simple);
    }
}
