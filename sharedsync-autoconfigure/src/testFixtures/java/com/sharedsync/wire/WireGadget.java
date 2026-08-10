package com.sharedsync.wire;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

import com.sharedsync.shared.annotation.CacheEntity;
import com.sharedsync.shared.annotation.CacheId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * wire 스키마 생성을 검증하기 위한 테스트 전용 엔티티.
 *
 * 필드 타입을 일부러 섞어 두었다 — 스키마 생성에서 실제로 문제가 되는 것들이다:
 * <ul>
 *   <li>참조 타입(UUID/String/Enum): proto3 optional 이 붙어야 부분 병합이 성립한다</li>
 *   <li>자바 원시 타입(int/boolean): optional 이 붙으면 안 된다(항상 값이 있다)</li>
 *   <li>BigDecimal/LocalTime: 문자열로 나가야 정밀도와 포맷이 보존된다</li>
 * </ul>
 */
@Entity
@Table(name = "wire_gadget")
@CacheEntity
public class WireGadget {

    @Id
    @CacheId
    @Column(name = "gadget_id")
    private UUID gadgetId;

    @Column(name = "label")
    private String label;

    /** 원시 타입 — presence 를 갖지 않아야 한다. */
    @Column(name = "slot_count")
    private int slotCount;

    /** 박스 타입 — presence 를 가져야 한다. */
    @Column(name = "weight")
    private Integer weight;

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "opens_at")
    private LocalTime opensAt;

    @Column(name = "shape")
    private GadgetShape shape;

    public UUID getGadgetId() {
        return gadgetId;
    }

    public void setGadgetId(UUID gadgetId) {
        this.gadgetId = gadgetId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getSlotCount() {
        return slotCount;
    }

    public void setSlotCount(int slotCount) {
        this.slotCount = slotCount;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public LocalTime getOpensAt() {
        return opensAt;
    }

    public void setOpensAt(LocalTime opensAt) {
        this.opensAt = opensAt;
    }

    public GadgetShape getShape() {
        return shape;
    }

    public void setShape(GadgetShape shape) {
        this.shape = shape;
    }
}
