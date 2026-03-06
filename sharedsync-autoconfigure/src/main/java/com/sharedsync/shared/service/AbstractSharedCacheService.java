package com.sharedsync.shared.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.dto.WRequest;
import com.sharedsync.shared.dto.WResponse;
import com.sharedsync.shared.history.HistoryAction;
import com.sharedsync.shared.history.HistoryService;
import com.sharedsync.shared.repository.AutoCacheRepository;

/**
 * Template-style base service that encapsulates the common create/update/delete
 * workflow shared by the distributed shared services. Concrete services supply
 * request/response wiring and ID handling via the constructor arguments.
 */
public abstract class AbstractSharedCacheService<Req extends WRequest, Res extends WResponse, DTO extends CacheDto<ID>, ID>
        implements SharedService<Req, Res> {

    @Autowired
    protected HistoryService historyService;

    private final AutoCacheRepository<?, ID, DTO> cacheRepository;
    private final String entityName;
    private final Function<Req, List<DTO>> requestExtractor;
    private final BiConsumer<Res, List<DTO>> responseWriter;
    private final Supplier<Res> responseFactory;
    private final Function<DTO, ID> idExtractor;
    private final UnaryOperator<DTO> createTransformer;
    private final UnaryOperator<DTO> updateTransformer;

    protected AbstractSharedCacheService(
            AutoCacheRepository<?, ID, DTO> cacheRepository,
            String entityName,
            Function<Req, List<DTO>> requestExtractor,
            BiConsumer<Res, List<DTO>> responseWriter,
            Supplier<Res> responseFactory,
            Function<DTO, ID> idExtractor,
            UnaryOperator<DTO> createTransformer,
            UnaryOperator<DTO> updateTransformer) {
        this.cacheRepository = cacheRepository;
        this.entityName = entityName;
        this.requestExtractor = requestExtractor;
        this.responseWriter = responseWriter;
        this.responseFactory = responseFactory;
        this.idExtractor = idExtractor;
        this.createTransformer = createTransformer != null ? createTransformer : UnaryOperator.identity();
        this.updateTransformer = updateTransformer != null ? updateTransformer : UnaryOperator.identity();
    }

    @Override
    public Res read(Req request) {
        throw new UnsupportedOperationException("Read operation is not implemented for AbstractSharedCacheService. Override in subclasses if needed.");
    }

    @Override
    public Object undo(String rootId) {
        if (historyService != null) {
            return historyService.undo(rootId);
        }
        return null;
    }

    @Override
    public Object redo(String rootId) {
        if (historyService != null) {
            return historyService.redo(rootId);
        }
        return null;
    }

    @Override
    public Res create(Req request) {
        Res response = responseFactory.get();
        List<DTO> payload = safePayload(request);

        if (payload.isEmpty()) {
            responseWriter.accept(response, Collections.emptyList());
            return response;
        }

        List<DTO> prepared = payload.stream()
                .map(createTransformer)
                .collect(Collectors.toList());

        List<DTO> saved = cacheRepository.saveAll(prepared);

        recordHistory(request, HistoryAction.Type.CREATE, null, saved);

        responseWriter.accept(response, saved);
        return response;
    }

    @Override
    public Res update(Req request) {
        Res response = responseFactory.get();
        List<DTO> payload = safePayload(request);

        if (payload.isEmpty()) {
            responseWriter.accept(response, Collections.emptyList());
            return response;
        }

        List<DTO> before = payload.stream()
                .map(idExtractor)
                .map(cacheRepository::findDtoById)
                .collect(Collectors.toList());

        List<DTO> updated = payload.stream()
                .map(updateTransformer)
                .map(cacheRepository::update)
                .collect(Collectors.toList());

        recordHistory(request, HistoryAction.Type.UPDATE, before, updated);

        responseWriter.accept(response, updated);
        return response;
    }

    @Override
    public Res delete(Req request) {
        Res response = responseFactory.get();
        List<DTO> payload = safePayload(request);

        if (payload.isEmpty()) {
            responseWriter.accept(response, Collections.emptyList());
            return response;
        }

        List<DTO> before = payload.stream()
                .map(idExtractor)
                .map(cacheRepository::findDtoById)
                .collect(Collectors.toList());

        List<ID> ids = payload.stream()
                .map(idExtractor)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<HistoryAction> subActions = new ArrayList<>();
        if (!HistoryService.isSkipHistory()) {
            for (ID id : ids) {
                subActions.addAll(cacheRepository.collectCascadedHistory(id));
            }
        }

        if (!ids.isEmpty()) {
            cacheRepository.deleteAllById(ids);
        }

        recordHistory(request, HistoryAction.Type.DELETE, before, null, subActions);

        responseWriter.accept(response, payload);
        return response;
    }

    private void recordHistory(Req request, HistoryAction.Type type, List<DTO> before, List<DTO> after) {
        recordHistory(request, type, before, after, null);
    }

    @SuppressWarnings("unchecked")
    private void recordHistory(Req request, HistoryAction.Type type, List<DTO> before, List<DTO> after, List<HistoryAction> subActions) {
        if (HistoryService.isSkipHistory() || historyService == null || request.getRootId() == null) return;

        historyService.record(request.getRootId(), HistoryAction.builder()
                .type(type)
                .entityName(entityName)
                .dtoClassName(cacheRepository.getDtoClass().getName())
                .beforeData((List<? extends CacheDto<?>>) (List<?>) before)
                .afterData((List<? extends CacheDto<?>>) (List<?>) after)
                .subActions(subActions)
                .eventId(request.getEventId())
                .timestamp(System.currentTimeMillis())
                .build());
    }

    private List<DTO> safePayload(Req request) {
        List<DTO> payload = requestExtractor.apply(request);
        return payload != null ? payload : Collections.emptyList();
    }
}
