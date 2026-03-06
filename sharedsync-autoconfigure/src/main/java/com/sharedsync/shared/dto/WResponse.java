package com.sharedsync.shared.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WResponse {
    private String eventId;
    private String action;
    private String entity;
}
