package com.sharedsync.shared.service;

import com.sharedsync.shared.dto.WRequest;
import com.sharedsync.shared.dto.WResponse;

public interface SharedService<Req extends WRequest, Res extends WResponse> {

    public Res create(Req request);
    public Res read(Req request);
    public Res update(Req request);
    public Res delete(Req request);
    
    default Object undo(String rootId) {
        throw new UnsupportedOperationException("Undo is not supported");
    }
    
    default Object redo(String rootId) {
        throw new UnsupportedOperationException("Redo is not supported");
    }
}
