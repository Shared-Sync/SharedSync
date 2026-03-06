package com.sharedsync.shared.controller;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Payload;

import com.sharedsync.shared.dto.WRequest;
import com.sharedsync.shared.dto.WResponse;
import com.sharedsync.shared.service.SharedService;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public abstract class SharedController<req extends WRequest, res extends WResponse, T extends SharedService<req, res>> {
    protected final T service;

    protected res handleCreate(@DestinationVariable String rootEntityId, @Payload req request) {
        request.setRootId(rootEntityId);
        res response = service.create(request);
        response.setEventId(request.getEventId() == null ? "" : request.getEventId());
        return response;
    }

    protected res handleRead(@DestinationVariable String rootEntityId, @Payload req request) {
        request.setRootId(rootEntityId);
        res response = service.read(request);
        response.setEventId(request.getEventId() == null ? "" : request.getEventId());
        return response;
    }

    protected res handleUpdate(@DestinationVariable String rootEntityId, @Payload req request) {
        request.setRootId(rootEntityId);
        res response = service.update(request);
        response.setEventId(request.getEventId() == null ? "" : request.getEventId());
        return response;
    }

    protected res handleDelete(@DestinationVariable String rootEntityId, @Payload req request) {
        request.setRootId(rootEntityId);
        res response = service.delete(request);
        response.setEventId(request.getEventId() == null ? "" : request.getEventId());
        return response;
    }

    protected Object handleUndo(@DestinationVariable String rootEntityId) {
        return service.undo(rootEntityId);
    }

    protected Object handleRedo(@DestinationVariable String rootEntityId) {
        return service.redo(rootEntityId);
    }

}
