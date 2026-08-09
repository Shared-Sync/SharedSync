package com.sharedsync.generator;

import java.io.IOException;
import java.io.Writer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileObject;

import com.sharedsync.generator.Generator.CacheInformation;

public class ControllerGenerator {
	private static final String OBJECT_NAME = "controller";

	public static void initialize(CacheInformation cacheInfo) {
		cacheInfo.setControllerClassName("Shared" + cacheInfo.getEntityName() + Generator.capitalizeFirst(OBJECT_NAME));
		cacheInfo.setControllerPath(cacheInfo.getBasicPackagePath() + "." + OBJECT_NAME);
	}

	public static boolean process(CacheInformation cacheInfo, ProcessingEnvironment processingEnv) {
		return true;
	}

	public static void generateUnified(java.util.List<CacheInformation> cacheInfoList,
			ProcessingEnvironment processingEnv) {
		if (cacheInfoList.isEmpty())
			return;

		StringBuilder source = new StringBuilder();
		source.append("package sharedsync.controller;\n\n");

		source.append("import org.springframework.messaging.handler.annotation.MessageMapping;\n");
		source.append("import org.springframework.messaging.handler.annotation.SendTo;\n");
		source.append("import org.springframework.messaging.handler.annotation.DestinationVariable;\n");
		source.append("import org.springframework.messaging.handler.annotation.Payload;\n");
		source.append("import org.springframework.stereotype.Controller;\n");
		source.append("import com.fasterxml.jackson.databind.ObjectMapper;\n");
		source.append("import com.sharedsync.shared.dto.WResponse;\n");
		source.append("import com.sharedsync.shared.dto.WRequest;\n");
		source.append("import com.sharedsync.shared.history.HistoryAction;\n");
		source.append("import com.sharedsync.shared.sync.RedisSyncService;\n");
		source.append("import com.sharedsync.shared.history.HistoryService;\n");
		source.append("import com.sharedsync.shared.storage.PresenceStorage;\n");
		source.append("import com.sharedsync.shared.transport.SyncSessionContext;\n");
		source.append("import com.sharedsync.shared.controller.SyncDispatcher;\n\n");

		for (CacheInformation info : cacheInfoList) {
			source.append("import ").append(info.getRequestPath()).append(".").append(info.getRequestClassName())
					.append(";\n");
			source.append("import ").append(info.getResponsePath()).append(".").append(info.getResponseClassName())
					.append(";\n");
			source.append("import ").append(info.getServicePath()).append(".").append(info.getServiceClassName())
					.append(";\n");
			source.append("import ").append(info.getDtoPath()).append(".").append(info.getDtoClassName())
					.append(";\n");
		}
		source.append("\n");

		source.append("@Controller\n");
		source.append("public class SharedSyncController implements SyncDispatcher {\n\n");

		source.append("    private final ObjectMapper objectMapper;\n");
		source.append("    private final RedisSyncService redisSyncService;\n");
		source.append("    private final HistoryService historyService;\n");
		source.append("    private final PresenceStorage presenceStorage;\n");
		source.append("    private final SyncSessionContext sessionContext;\n");
		for (CacheInformation info : cacheInfoList) {
			String serviceVar = decapitalizeFirst(info.getServiceClassName());
			source.append("    private final ").append(info.getServiceClassName()).append(" ").append(serviceVar)
					.append(";\n");
		}
		source.append("\n");

		source.append(
				"    public SharedSyncController(ObjectMapper objectMapper, RedisSyncService redisSyncService, HistoryService historyService, PresenceStorage presenceStorage, SyncSessionContext sessionContext");
		for (CacheInformation info : cacheInfoList) {
			source.append(", ").append(info.getServiceClassName()).append(" ")
					.append(decapitalizeFirst(info.getServiceClassName()));
		}
		source.append(") {\n");
		source.append("        this.objectMapper = objectMapper;\n");
		source.append("        this.redisSyncService = redisSyncService;\n");
		source.append("        this.historyService = historyService;\n");
		source.append("        this.presenceStorage = presenceStorage;\n");
		source.append("        this.sessionContext = sessionContext;\n");
		for (CacheInformation info : cacheInfoList) {
			String serviceVar = decapitalizeFirst(info.getServiceClassName());
			source.append("        this.").append(serviceVar).append(" = ").append(serviceVar).append(";\n");
		}
		source.append("    }\n\n");

		// STOMP 진입점. 목적지 파싱만 담당하고 실제 처리는 transport 중립인 dispatch 로 넘긴다.
		source.append("    @MessageMapping(\"/{roomId}\")\n");
		source.append("    public void handle(@DestinationVariable(\"roomId\") String roomId, \n");
		source.append("                         @Payload java.util.Map<String, Object> payload) {\n");
		source.append("        dispatch(roomId, payload);\n");
		source.append("    }\n\n");

		// ── JSON(Map) 경로 ────────────────────────────────────────────────
		source.append("    @Override\n");
		source.append("    public void dispatch(String roomId, java.util.Map<String, Object> payload) {\n\n");
		source.append("        if (!authorized(roomId)) return;\n\n");
		source.append("        String action = (String) payload.get(\"action\");\n");
		source.append("        if (handleHistory(roomId, action)) return;\n\n");
		source.append("        String entity = (String) payload.get(\"entity\");\n");
		source.append("        if (entity == null) return;\n\n");
		source.append("        Object result = null;\n");
		source.append("        switch (entity.toLowerCase()) {\n");
		for (CacheInformation info : cacheInfoList) {
			source.append("            case \"").append(info.getEntityName().toLowerCase()).append("\": {\n");
			source.append("                ").append(info.getRequestClassName())
					.append(" request = objectMapper.convertValue(payload, ").append(info.getRequestClassName())
					.append(".class);\n");
			source.append("                result = ").append(runMethodName(info)).append("(request, roomId);\n");
			source.append("                break;\n");
			source.append("            }\n");
		}
		source.append("            default:\n");
		source.append("                break;\n");
		source.append("        }\n");
		source.append("        publish(roomId, result);\n");
		source.append("    }\n\n");

		// ── protobuf(ClientFrame) 경로 ────────────────────────────────────
		source.append("    /**\n");
		source.append("     * raw WebSocket 경로. Map 을 거치지 않고 DynamicMessage 에서 DTO 로 바로 간다 —\n");
		source.append("     * 중간에 Map 을 두면 proto 의 명시적 presence 정보가 사라져 부분 병합이 깨진다.\n");
		source.append("     */\n");
		source.append("    @Override\n");
		source.append("    public void dispatch(String roomId, com.sharedsync.shared.codec.ClientFrame.Edit edit) {\n\n");
		source.append("        if (!authorized(roomId)) return;\n\n");
		source.append("        String action = edit.action();\n");
		source.append("        if (handleHistory(roomId, action)) return;\n\n");
		source.append("        String entity = edit.entity();\n");
		source.append("        if (entity == null) return;\n\n");
		source.append("        Object result = null;\n");
		source.append("        switch (entity.toLowerCase()) {\n");
		for (CacheInformation info : cacheInfoList) {
			String dtoClass = info.getDtoClassName();
			source.append("            case \"").append(info.getEntityName().toLowerCase()).append("\": {\n");
			source.append("                ").append(info.getRequestClassName()).append(" request = new ")
					.append(info.getRequestClassName()).append("();\n");
			source.append("                request.setAction(action);\n");
			source.append("                request.setEntity(entity);\n");
			source.append("                request.setEventId(edit.eventId());\n");
			source.append("                java.util.List<").append(dtoClass).append("> dtos = new java.util.ArrayList<>();\n");
			source.append("                for (com.google.protobuf.DynamicMessage item : edit.items()) {\n");
			source.append("                    dtos.add(com.sharedsync.shared.codec.DtoProtoMapper.toDto(item, ")
					.append(dtoClass).append(".class));\n");
			source.append("                }\n");
			source.append("                request.set").append(dtoClass).append("s(dtos);\n");
			source.append("                result = ").append(runMethodName(info)).append("(request, roomId);\n");
			source.append("                break;\n");
			source.append("            }\n");
		}
		source.append("            default:\n");
		source.append("                break;\n");
		source.append("        }\n");
		source.append("        publish(roomId, result);\n");
		source.append("    }\n\n");

		// ── 두 경로가 공유하는 부분 ────────────────────────────────────────
		source.append("    /** 이 세션이 실제로 그 룸에 있는지. transport 가 무엇이든 세션->룸 매핑이 근거다. */\n");
		source.append("    private boolean authorized(String roomId) {\n");
		source.append("        String sessionId = sessionContext.currentSessionId();\n");
		source.append("        String mappedRoomId = presenceStorage.getRootIdBySessionId(sessionId);\n");
		source.append("        return mappedRoomId != null && mappedRoomId.equals(roomId);\n");
		source.append("    }\n\n");

		source.append("    /** undo/redo 는 엔티티 분기 없이 히스토리 서비스가 직접 처리하고 스스로 publish 한다. */\n");
		source.append("    private boolean handleHistory(String roomId, String action) {\n");
		source.append("        if (\"undo\".equalsIgnoreCase(action)) {\n");
		source.append("            historyService.undo(roomId);\n");
		source.append("            return true;\n");
		source.append("        }\n");
		source.append("        if (\"redo\".equalsIgnoreCase(action)) {\n");
		source.append("            historyService.redo(roomId);\n");
		source.append("            return true;\n");
		source.append("        }\n");
		source.append("        return false;\n");
		source.append("    }\n\n");

		source.append("    private void publish(String roomId, Object result) {\n");
		source.append("        if (result != null) {\n");
		source.append("            redisSyncService.publish(\"/topic/\" + roomId, result);\n");
		source.append("        }\n");
		source.append("    }\n\n");

		// 엔티티별 실행부. 응답을 타입화된 엔벨로프로 감싸 일반 편집과 undo/redo 가 같은 모양으로 나가게 한다.
		for (CacheInformation info : cacheInfoList) {
			String serviceVar = decapitalizeFirst(info.getServiceClassName());
			source.append("    private Object ").append(runMethodName(info)).append("(")
					.append(info.getRequestClassName()).append(" request, String roomId) {\n");
			source.append("        request.setRootId(roomId);\n");
			source.append("        Object res = handleAction(").append(serviceVar).append(", request);\n");
			source.append("        if (res instanceof ").append(info.getResponseClassName()).append(" typed) {\n");
			source.append("            return new com.sharedsync.shared.codec.SyncOutbound.Entities(\n");
			source.append("                    typed.getEventId(), typed.getAction(), typed.getEntity(), false,\n");
			source.append("                    \"").append(decapitalizeFirst(info.getDtoClassName())).append("s\",\n");
			source.append("                    typed.get").append(info.getDtoClassName()).append("s());\n");
			source.append("        }\n");
			source.append("        return null;\n");
			source.append("    }\n\n");
		}

		source.append(
				"    private <Req extends WRequest, Res extends WResponse> Object handleAction(com.sharedsync.shared.service.SharedService<Req, Res> service, Req request) {\n");
		source.append("        String action = request.getAction();\n");
		source.append("        if (action == null) return null;\n\n");
		source.append("        Object result = switch (action.toLowerCase()) {\n");
		source.append("            case \"create\" -> service.create(request);\n");
		source.append("            case \"read\" -> service.read(request);\n");
		source.append("            case \"update\" -> service.update(request);\n");
		source.append("            case \"delete\" -> service.delete(request);\n");
		source.append("            default -> null;\n");
		source.append("        };\n");
		source.append("        if (result instanceof WResponse response) {\n");
		source.append("            response.setAction(action);\n");
		source.append("            response.setEntity(request.getEntity());\n");
		source.append("            response.setEventId(request.getEventId() == null ? \"\" : request.getEventId());\n");
		source.append("        }\n");
		source.append("        return result;\n");
		source.append("    }\n");

		source.append("}\n");

		// 파일 생성
		try {
			JavaFileObject file = processingEnv.getFiler()
					.createSourceFile("sharedsync.controller.SharedSyncController");
			Writer writer = file.openWriter();
			writer.write(source.toString());
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/** 엔티티별 실행부 메서드명. 두 dispatch 경로가 같은 메서드로 수렴한다. */
	private static String runMethodName(CacheInformation info) {
		return "run" + info.getEntityName();
	}

	private static String decapitalizeFirst(String str) {
		if (str == null || str.isEmpty())
			return "";
		return str.substring(0, 1).toLowerCase() + str.substring(1);
	}

	private static String capitalizeFirst(String str) {
		if (str == null || str.isEmpty())
			return "";
		return str.substring(0, 1).toUpperCase() + str.substring(1);
	}
}
