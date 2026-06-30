package com.sharedsync.shared.repository.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

/**
 * {@link IdTypeConverter} 가 {@code BigInteger} 타입 PK를 지원하는지 고정하는 단위 테스트.
 *
 * <p>스프링/Redis 비의존 순수 경로만 검증한다(엔티티 @Id 필드는 리플렉션으로 직접 주입).
 * Long 범위를 초과하는 값까지 손실 없이 왕복되는지 함께 확인한다.</p>
 */
class IdTypeConverterBigIntegerTest {

    /** BigInteger @Id 를 가진 최소 엔티티 (필드 리플렉션 용). */
    static class BigIntEntity {
        BigInteger id;
    }

    private IdTypeConverter<BigIntEntity, BigInteger> converter(boolean useIdPool) throws Exception {
        Field idField = BigIntEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        return new IdTypeConverter<>(BigInteger.class, idField, useIdPool);
    }

    @Test
    void changeType_fromLong_returnsBigInteger() throws Exception {
        Object result = converter(true).changeType(123L);
        assertThat(result).isEqualTo(BigInteger.valueOf(123L));
    }

    @Test
    void changeType_fromString_returnsBigInteger() throws Exception {
        Object result = converter(true).changeType("456");
        assertThat(result).isEqualTo(new BigInteger("456"));
    }

    @Test
    void changeType_alreadyBigInteger_passesThrough() throws Exception {
        BigInteger value = new BigInteger("789");
        assertThat(converter(true).changeType(value)).isSameAs(value);
    }

    @Test
    void convertStringToId_returnsBigInteger() throws Exception {
        assertThat(converter(true).convertStringToId("100")).isEqualTo(BigInteger.valueOf(100L));
    }

    @Test
    void convertStringToId_beyondLongRange_keepsFullPrecision() throws Exception {
        String huge = "99999999999999999999999999999999"; // > Long.MAX_VALUE
        assertThat(converter(true).convertStringToId(huge)).isEqualTo(new BigInteger(huge));
    }

    @Test
    void convertIdToType_toBigInteger() throws Exception {
        Object result = converter(true).convertIdToType(BigInteger.class, 250L);
        assertThat(result).isEqualTo(BigInteger.valueOf(250L));
    }

    @Test
    void extractAndSetEntityId_roundTrip() throws Exception {
        IdTypeConverter<BigIntEntity, BigInteger> c = converter(true);
        BigIntEntity entity = new BigIntEntity();
        c.setEntityId(entity, BigInteger.valueOf(42L));
        assertThat(c.extractEntityId(entity)).isEqualTo(BigInteger.valueOf(42L));
    }

    @Test
    void isTemporaryId_negativeBigInteger_whenNoIdPool() throws Exception {
        assertThat(converter(false).isTemporaryId(BigInteger.valueOf(-5L))).isTrue();
        assertThat(converter(false).isTemporaryId(BigInteger.valueOf(7L))).isFalse();
    }

    @Test
    void isTemporaryId_alwaysFalse_whenIdPool() throws Exception {
        assertThat(converter(true).isTemporaryId(BigInteger.valueOf(-5L))).isFalse();
    }
}
