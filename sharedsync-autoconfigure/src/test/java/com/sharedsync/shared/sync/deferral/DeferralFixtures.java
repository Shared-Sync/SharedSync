package com.sharedsync.shared.sync.deferral;

import java.util.UUID;

import com.sharedsync.shared.annotation.Cache;
import com.sharedsync.shared.annotation.CacheId;
import com.sharedsync.shared.annotation.EntityConverter;
import com.sharedsync.shared.annotation.ParentId;
import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.repository.AutoCacheRepository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 유니크 제약 지연 테스트용 계층: Shelf(루트) → Slot(NOT NULL FK, 선반 안에서 위치 유니크) → Item(NOT NULL FK).
 *
 * <p>planmate 의 plan → time_table(plan_id, date 유니크) → time_table_place_block(time_table_id, block_start_time
 * 유니크) 와 같은 모양이다. 위치 유니크 제약은 Hibernate 가 지연 불가로 만들고, 테스트가
 * {@code DEFERRABLE INITIALLY IMMEDIATE} 로 다시 만든다(앱의 마이그레이션과 같은 일).</p>
 */
final class DeferralFixtures {

    static final String SLOT_UNIQUE = "uk_def_slot_position";

    private DeferralFixtures() {
    }

    // ===== 엔티티 =====

    @Entity
    @Table(name = "def_shelf")
    public static class Shelf {
        @Id
        @Column(name = "shelf_id")
        private UUID shelfId;

        @Column(name = "name")
        private String name;

        public Shelf() {
        }

        public Shelf(UUID shelfId, String name) {
            this.shelfId = shelfId;
            this.name = name;
        }

        public UUID getShelfId() {
            return shelfId;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "def_slot", uniqueConstraints = @UniqueConstraint(name = SLOT_UNIQUE,
            columnNames = { "shelf_id", "slot_position" }))
    public static class Slot {
        @Id
        @Column(name = "slot_id")
        private UUID slotId;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "shelf_id", nullable = false)
        private Shelf shelf;

        @Column(name = "slot_position")
        private Integer position;

        @Column(name = "label")
        private String label;

        public Slot() {
        }

        public Slot(UUID slotId, Shelf shelf, Integer position, String label) {
            this.slotId = slotId;
            this.shelf = shelf;
            this.position = position;
            this.label = label;
        }

        public UUID getSlotId() {
            return slotId;
        }

        public Shelf getShelf() {
            return shelf;
        }

        public Integer getPosition() {
            return position;
        }

        public String getLabel() {
            return label;
        }
    }

    @Entity
    @Table(name = "def_item")
    public static class Item {
        @Id
        @Column(name = "item_id")
        private UUID itemId;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "slot_id", nullable = false)
        private Slot slot;

        @Column(name = "title")
        private String title;

        public Item() {
        }

        public Item(UUID itemId, Slot slot, String title) {
            this.itemId = itemId;
            this.slot = slot;
            this.title = title;
        }

        public UUID getItemId() {
            return itemId;
        }

        public Slot getSlot() {
            return slot;
        }

        public String getTitle() {
            return title;
        }
    }

    // ===== DTO =====

    @Cache(keyType = "defshelf")
    public static class ShelfDto extends CacheDto<UUID> {
        @CacheId
        public UUID shelfId;
        public String name;

        @Override
        public UUID getId() {
            return shelfId;
        }

        @EntityConverter
        public Shelf toEntity() {
            return new Shelf(shelfId, name);
        }

        public static ShelfDto fromEntity(Shelf e) {
            ShelfDto dto = new ShelfDto();
            dto.shelfId = e.getShelfId();
            dto.name = e.getName();
            return dto;
        }
    }

    @Cache(keyType = "defslot")
    public static class SlotDto extends CacheDto<UUID> {
        @CacheId
        public UUID slotId;
        @ParentId(Shelf.class)
        public UUID shelfId;
        public Integer position;
        public String label;

        static SlotDto of(UUID slotId, UUID shelfId, Integer position, String label) {
            SlotDto dto = new SlotDto();
            dto.slotId = slotId;
            dto.shelfId = shelfId;
            dto.position = position;
            dto.label = label;
            return dto;
        }

        @Override
        public UUID getId() {
            return slotId;
        }

        @EntityConverter
        public Slot toEntity(Shelf shelf) {
            return new Slot(slotId, shelf, position, label);
        }

        public static SlotDto fromEntity(Slot e) {
            return of(e.getSlotId(), e.getShelf().getShelfId(), e.getPosition(), e.getLabel());
        }
    }

    @Cache(keyType = "defitem")
    public static class ItemDto extends CacheDto<UUID> {
        @CacheId
        public UUID itemId;
        @ParentId(Slot.class)
        public UUID slotId;
        public String title;

        static ItemDto of(UUID itemId, UUID slotId, String title) {
            ItemDto dto = new ItemDto();
            dto.itemId = itemId;
            dto.slotId = slotId;
            dto.title = title;
            return dto;
        }

        @Override
        public UUID getId() {
            return itemId;
        }

        @EntityConverter
        public Item toEntity(Slot slot) {
            return new Item(itemId, slot, title);
        }

        public static ItemDto fromEntity(Item e) {
            return of(e.getItemId(), e.getSlot().getSlotId(), e.getTitle());
        }
    }

    // ===== 캐시 리포지토리 =====

    public static class ShelfRepository extends AutoCacheRepository<Shelf, UUID, ShelfDto> {
    }

    public static class SlotRepository extends AutoCacheRepository<Slot, UUID, SlotDto> {
    }

    public static class ItemRepository extends AutoCacheRepository<Item, UUID, ItemDto> {
    }
}
