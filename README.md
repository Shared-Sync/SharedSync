# SharedSync 🚀

SharedSync는 **Spring Boot** 환경에서 실시간 협업 편집 기능을 손쉽게 구현할 수 있도록 돕는 프레임워크입니다. WebSocket 기술과 Redis를 결합하여 여러 사용자가 동시에 데이터를 수정하고 이를 실시간으로 동기화하며, 사용자 상태(Presence) 관리 및 실행 취소(Undo)/재실행(Redo) 기능을 제공합니다.

---

## 주요 기능 (Features)

1. **실시간 데이터 동기화 (Real-time Sync)**
   - WebSocket을 기반으로 클라이언트 간 데이터 변경 사항을 실시간으로 주고받습니다.
   - 추상화된 `SharedController`와 `SharedService`를 제공하여 CRUD 로직만 작성하면 동기화 기능이 자동으로 구현됩니다.

2. **사용자 상태 관리 (Presence Tracking)**
   - 어떤 사용자가 현재 온라인인지, 어떤 방(Root Entity)에 접속해 있는지 실시간으로 추적합니다.
   - 세션 타임아웃 및 자동 클린업 기능을 지원합니다.

3. **실행 취소 및 재실행 (Undo/Redo)**
   - 데이터 변경 이력을 관리하여 협업 환경에서의 Undo/Redo 로직을 내장하고 있습니다.

4. **멀티 서버 지원 (Scaling with Redis)**
   - Redis Pub/Sub을 활용하여 여러 대의 서버로 구성된 분산 환경에서도 서버 간 WebSocket 메시지 동기화를 지원합니다.

5. **자동 코드 생성 (Annotation Processing)**
   - `@CacheEntity` 등 커스텀 어노테이션을 통해 협업에 필요한 DTO, Controller, Service 코드의 스캐폴딩을 자동으로 생성합니다.

6. **데이터베이스 자동 연동**
   - `@AutoDatabaseLoader` 등을 통해 캐시(Redis/Local)와 실제 데이터베이스(JPA 등) 간의 데이터 로딩 및 저장을 자동화할 수 있습니다.

---

## 설치 방법 (Installation)

`sharedsync`는 Gradle 멀티 모듈 프로젝트로 구성되어 있습니다. 사용하려는 프로젝트의 `build.gradle`에 다음과 같이 의존성을 추가합니다.

```gradle
dependencies {
    // SharedSync 스타터 추가
    implementation project(':sharedsync-starter')
    
    // Annotation Processor 추가 (코드 생성 기능을 위해 필요)
    annotationProcessor project(':sharedsync-autoconfigure')
}
```

---

## 환경 설정 (Environment Variables / Properties)

`application.yml` 또는 `application.properties`를 통해 다음과 같은 설정을 조정할 수 있습니다.

### WebSocket 설정 (`sharedsync.websocket`)
| 환경 변수 (Property) | 기본값 | 설명 |
| :--- | :--- | :--- |
| `sharedsync.websocket.endpoint` | `/ws-sharedsync` | WebSocket 연결 엔드포인트 경로 |
| `sharedsync.websocket.allowed-origins` | `*` | WebSocket 접속 허용 도메인 (CORS) |
| `sharedsync.websocket.redis-sync.enabled` | `false` | Redis Pub/Sub을 통한 서버 간 동기화 활성화 여부 |
| `sharedsync.websocket.redis-sync.channel` | `sharedsync:websocket:sync` | Redis 동기화용 채널명 |

### 사용자 상태 관리 설정 (`sharedsync.presence`)
| 환경 변수 (Property) | 기본값 | 설명 |
| :--- | :--- | :--- |
| `sharedsync.presence.enabled` | `true` | Presence 기능 사용 여부 |
| `sharedsync.presence.session-timeout` | `3600` | 세션 유효 시간 (초) |
| `sharedsync.presence.cleanup-interval` | `30` | 좀비 데이터 정리 주기 (초) |
| `sharedsync.presence.broadcast-delay` | `1000` | 구독 시작 시 최초 상태 전송 지연 시간(ms) |

### 보안 설정 (`sharedsync.auth`)
| 환경 변수 (Property) | 기본값 | 설명 |
| :--- | :--- | :--- |
| `sharedsync.auth.enabled` | `true` | WebSocket 연결 시 인증 절차 사용 여부 |

---

## 사용 방법 (How to Use)

### 1. 엔티티 정의 (`@CacheEntity`)
협업 대상이 되는 도메인 모델에 `@CacheEntity` 어노테이션을 부착합니다.

```java
@CacheEntity
@TableName("workspace_item")
public class WorkspaceItem {
    @CacheId
    private Long id;
    
    @ParentId
    private Long workspaceId;
    
    private String content;
    // ...
}
```

### 2. 컨트롤러 구현
`SharedController`를 상속받아 WebSocket 핸들러를 구성합니다. 프레임워크가 제공하는 기본 CRUD 핸들러를 활용할 수 있습니다.

```java
@Controller
@MessageMapping("/workspace/{rootId}")
public class MyCollaborativeController extends SharedController<MyRequest, MyResponse, MyService> {
    
    public MyCollaborativeController(MyService service) {
        super(service);
    }

    @MessageMapping("/create")
    @SendTo("/topic/workspace/{rootId}")
    public MyResponse create(@DestinationVariable int rootId, @Payload MyRequest request) {
        return super.handleCreate(rootId, request);
    }
    
    // Update, Delete, Undo/Redo 등도 동일한 방식으로 적용 가능
}
```

---

## 기술 스택 (Tech Stack)
- **Language:** Java 17
- **Framework:** Spring Boot 3.x
- **Communication:** Spring WebSocket, STOMP
- **Cache/Sync:** Redis (Lettuce), Spring Data Redis
- **Build Tool:** Gradle

---

## 라이선스 (License)
이 프로젝트는 [LICENSE](LICENSE) 파일에 정의된 라이선스를 따릅니다.

