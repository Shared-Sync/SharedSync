package com.sharedsync.shared.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class WRequest {
    private String eventId;
    private String action;
    private String entity;
    private String rootId;
}
