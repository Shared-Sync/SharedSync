package com.sharedsync.shared.history;

import java.util.List;

import com.sharedsync.shared.dto.CacheDto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoryAction {
    public enum Type {
        CREATE, UPDATE, DELETE
    }

    private Type type;
    private String entityName;
    private String dtoClassName;
    private List<? extends CacheDto<?>> beforeData;
    private List<? extends CacheDto<?>> afterData;
    private List<HistoryAction> subActions; // 자식 엔티티들에 대한 히스토리
    private String eventId;
    private long timestamp;
}
