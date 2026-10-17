package com.sharedsync.shared.sync.itest;

import java.util.UUID;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

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
 * 동기화 통합 테스트용 계층: Board(루트) → Lane(NOT NULL FK, board 안에서 key 유니크) → Card(NOT NULL FK),
 * Lane ← Note(NULL 허용 FK, DB 가 ON DELETE SET NULL).
 *
 * <p>FK 에 ON DELETE CASCADE 를 걸지 않는다 — DB 가 자식을 대신 지워 주지 않는 앱에서도 부모 삭제가 되는지
 * 보기 위해서다. planmate 의 plan → time_table → time_table_place_block, uk_time_table_plan_date 와 같은 모양.</p>
 *
 * <p>NOT NULL 참조는 planmate 와 똑같이 {@code @ManyToOne}(optional 기본값) + {@code @JoinColumn(nullable = false)}
 * 로 둔다. Hibernate 는 이 경우 "삭제된 부모를 가리키는 자식"을 지울 때 FK 를 먼저 null 로 UPDATE 하는데,
 * 그게 DB NOT NULL 에 걸리는 것이 버그 3이다. optional = false 로 두면 그 UPDATE 가 나가지 않아 재현되지 않는다.</p>
 */
final class SyncFixtures {

    private SyncFixtures() {
    }

    // ===== 엔티티 =====

    @Entity
    @Table(name = "sync_board")
    public static class Board {
        @Id
        @Column(name = "board_id")
        private UUID boardId;

        @Column(name = "name")
        private String name;

        public Board() {
        }

        public Board(UUID boardId, String name) {
            this.boardId = boardId;
            this.name = name;
        }

        public UUID getBoardId() {
            return boardId;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "sync_lane", uniqueConstraints = @UniqueConstraint(name = "uk_sync_lane_board_key",
            columnNames = { "board_id", "lane_key" }))
    public static class Lane {
        @Id
        @Column(name = "lane_id")
        private UUID laneId;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "board_id", nullable = false)
        private Board board;

        @Column(name = "lane_key")
        private String laneKey;

        public Lane() {
        }

        public Lane(UUID laneId, Board board, String laneKey) {
            this.laneId = laneId;
            this.board = board;
            this.laneKey = laneKey;
        }

        public UUID getLaneId() {
            return laneId;
        }

        public Board getBoard() {
            return board;
        }

        public String getLaneKey() {
            return laneKey;
        }
    }

    @Entity
    @Table(name = "sync_card")
    public static class Card {
        @Id
        @Column(name = "card_id")
        private UUID cardId;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "lane_id", nullable = false)
        private Lane lane;

        @Column(name = "title")
        private String title;

        public Card() {
        }

        public Card(UUID cardId, Lane lane, String title) {
            this.cardId = cardId;
            this.lane = lane;
            this.title = title;
        }

        public UUID getCardId() {
            return cardId;
        }

        public Lane getLane() {
            return lane;
        }

        public String getTitle() {
            return title;
        }
    }

    @Entity
    @Table(name = "sync_note")
    public static class Note {
        @Id
        @Column(name = "note_id")
        private UUID noteId;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "lane_id")
        @OnDelete(action = OnDeleteAction.SET_NULL)
        private Lane lane;

        @Column(name = "text")
        private String text;

        public Note() {
        }

        public Note(UUID noteId, Lane lane, String text) {
            this.noteId = noteId;
            this.lane = lane;
            this.text = text;
        }

        public UUID getNoteId() {
            return noteId;
        }

        public Lane getLane() {
            return lane;
        }
    }

    // ===== DTO =====

    @Cache(keyType = "syncboard")
    public static class BoardDto extends CacheDto<UUID> {
        @CacheId
        public UUID boardId;
        public String name;

        @Override
        public UUID getId() {
            return boardId;
        }

        @EntityConverter
        public Board toEntity() {
            return new Board(boardId, name);
        }

        public static BoardDto fromEntity(Board e) {
            BoardDto dto = new BoardDto();
            dto.boardId = e.getBoardId();
            dto.name = e.getName();
            return dto;
        }
    }

    @Cache(keyType = "synclane")
    public static class LaneDto extends CacheDto<UUID> {
        @CacheId
        public UUID laneId;
        @ParentId(Board.class)
        public UUID boardId;
        public String laneKey;

        static LaneDto of(UUID laneId, UUID boardId, String laneKey) {
            LaneDto dto = new LaneDto();
            dto.laneId = laneId;
            dto.boardId = boardId;
            dto.laneKey = laneKey;
            return dto;
        }

        @Override
        public UUID getId() {
            return laneId;
        }

        @EntityConverter
        public Lane toEntity(Board board) {
            return new Lane(laneId, board, laneKey);
        }

        public static LaneDto fromEntity(Lane e) {
            return of(e.getLaneId(), e.getBoard().getBoardId(), e.getLaneKey());
        }
    }

    @Cache(keyType = "synccard")
    public static class CardDto extends CacheDto<UUID> {
        @CacheId
        public UUID cardId;
        @ParentId(Lane.class)
        public UUID laneId;
        public String title;

        static CardDto of(UUID cardId, UUID laneId, String title) {
            CardDto dto = new CardDto();
            dto.cardId = cardId;
            dto.laneId = laneId;
            dto.title = title;
            return dto;
        }

        @Override
        public UUID getId() {
            return cardId;
        }

        @EntityConverter
        public Card toEntity(Lane lane) {
            return new Card(cardId, lane, title);
        }

        public static CardDto fromEntity(Card e) {
            return of(e.getCardId(), e.getLane().getLaneId(), e.getTitle());
        }
    }

    @Cache(keyType = "syncnote")
    public static class NoteDto extends CacheDto<UUID> {
        @CacheId
        public UUID noteId;
        @ParentId(Lane.class)
        public UUID laneId;
        public String text;

        @Override
        public UUID getId() {
            return noteId;
        }

        @EntityConverter
        public Note toEntity(Lane lane) {
            return new Note(noteId, lane, text);
        }

        public static NoteDto fromEntity(Note e) {
            NoteDto dto = new NoteDto();
            dto.noteId = e.getNoteId();
            dto.laneId = e.getLane() == null ? null : e.getLane().getLaneId();
            dto.text = e.text;
            return dto;
        }
    }

    // ===== 캐시 리포지토리 (생성 서브클래스와 동일하게 빈 본문) =====

    public static class BoardRepository extends AutoCacheRepository<Board, UUID, BoardDto> {
    }

    public static class LaneRepository extends AutoCacheRepository<Lane, UUID, LaneDto> {
    }

    public static class CardRepository extends AutoCacheRepository<Card, UUID, CardDto> {
    }

    public static class NoteRepository extends AutoCacheRepository<Note, UUID, NoteDto> {
    }
}
