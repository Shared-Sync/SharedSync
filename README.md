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

`application.yml` 또는 `application.properties`로 조정합니다.

### 전송 계층 (`sharedsync.websocket`)

| Property | 기본값 | 설명 |
| :--- | :--- | :--- |
| `transport` | `stomp` | `stomp`(현행) 또는 `websocket`(raw WebSocket + protobuf 바이너리 프레임) |
| `codec` | `json` | `json` 또는 `protobuf`. **transport=websocket 이면 자동으로 protobuf 가 된다** |
| `sockjs` | `true` | SockJS 폴백. **codec=protobuf 이면 자동으로 꺼진다**(바이너리 프레임과 양립 불가) |
| `endpoint` | `/ws-sharedsync` | WebSocket 연결 경로 |
| `allowed-origins` | `*` | 접속 허용 도메인 |
| `schema-path` | `<endpoint>/schema.proto` | 생성된 wire 스키마(.proto)를 서빙할 경로 |
| `ping-interval` | `25` | raw WS 모드에서 서버가 보내는 ping 주기(초). 0 이면 끈다 |
| `max-frame-size` | `262144` | 인바운드 프레임 상한(바이트). 컨테이너 기본값 8KB 를 대체한다 |
| `dispatch-threads` | `0` | 프레임 처리 스레드 수. 0 이면 코어 수 × 2 (최소 4) |
| `dispatch-queue-limit` | `200` | 세션당 대기 프레임 상한. 넘으면 `BACKPRESSURE` 에러를 돌려준다 |
| `redis-sync.enabled` | `false` | Redis Pub/Sub 서버 간 동기화 |
| `redis-sync.channel` | `sharedsync:websocket:sync` | 동기화 채널명 |

**protobuf 로 바꿀 때 앱이 할 일은 `transport: websocket` 한 줄이다.** codec 과 sockjs 는
프레임워크가 맞춘다(서로 성립하지 않는 조합이 있는데, 그걸 아는 것은 프레임워크의 몫이다).

### 사용자 상태 관리 (`sharedsync.presence`)

| Property | 기본값 | 설명 |
| :--- | :--- | :--- |
| `enabled` | `true` | Presence 기능 사용 여부 |
| `session-timeout` | `3600` | 세션 유효 시간(초) |
| `cleanup-interval` | `30` | 좀비 데이터 정리 주기(초) |
| `broadcast-delay` | `1000` | 구독 후 최초 상태 전송 지연(ms). raw WS 모드에서는 구독 경합이 없어 무시된다 |
| `sync-wait-attempts` / `sync-wait-interval-ms` | `10` / `500` | 진행 중인 DB flush 를 기다리는 폴링. 입장 경로를 그만큼 붙잡는다 |

### 보안 (`sharedsync.auth`)

| Property | 기본값 | 설명 |
| :--- | :--- | :--- |
| `enabled` | `true` | 연결 시 인증 절차 사용 여부 |
| `deny-unmatched` | `false` | 어떤 validator 도 매칭되지 않은 룸을 거부할지. `false` 면 통과한다 |

### 캐시 (`sharedsync.cache`)

| Property | 기본값 | 설명 |
| :--- | :--- | :--- |
| `type` | `memory` | `memory` 또는 `redis` |

---

## 사용 방법 (How to Use)

앱이 작성하는 것은 **엔티티 애노테이션과 인증 전략 두 가지**다. 컨트롤러·DTO·서비스·저장소는
애노테이션 프로세서가 생성한다.

### 1. 엔티티 정의

```java
@CacheEntity
@TableName("workspace_item")
public class WorkspaceItem {
    @CacheId
    private Long id;

    @ParentId
    private Long workspaceId;

    private String content;
}
```

협업 방의 루트가 되는 엔티티에 `@PresenceRoot(channel = "...")`, 접속자 정보를 노출할 엔티티에
`@PresenceUser(fields = {...})` 를 붙인다. 각각 하나씩이어야 하며, 둘 이상이면 컴파일이 깨진다.

> **필드 순서가 wire 계약이다.** proto 필드 번호는 선언 순서로 부여되므로, 엔티티 중간에 필드를
> 끼우면 클라이언트가 같은 바이트를 다른 필드로 읽는다(파싱은 성공하므로 예외가 없다).
> 빌드가 만들어주는 `sharedsync-wire.lock` 을 `src/main/resources/` 에 커밋해두면 그런 변경을
> 컴파일 시점에 잡아준다. **새 필드는 항상 맨 뒤에 선언할 것.**

### 2. 인증 전략

`AuthenticationTokenResolver` 를 빈으로 등록한다(`sharedsync.auth.enabled=false` 면 생략 가능).
룸 접근 인가가 필요하면 `SyncAccessValidator`(또는 기존 `StompAccessValidator`)를 구현한다.

```java
@Component
public class RoomAccessValidator implements SyncAccessValidator {
    @Override
    public void validate(String userId, String roomId, String channel) {
        // 통과시키지 않으려면 예외를 던진다
    }
}
```

핸드셰이크 토큰은 세 곳에서 읽는다. 브라우저는 헤더를 지정할 수 없으므로 두 번째를 쓴다:

1. `Authorization: Bearer <token>` — 서버 대 서버
2. `Sec-WebSocket-Protocol: sharedsync.v1, bearer.<token>` — 브라우저
3. `?token=<token>` — 하위 호환. **URL 은 액세스 로그에 남으므로 권장하지 않는다**

> raw WebSocket + protobuf 전송의 클라이언트 계약(프레임 순서, 에러 코드, 백프레셔, 스키마 협상)은
> [docs/wire-protocol.md](docs/wire-protocol.md) 에 있다.

### 3. 프레임워크가 제공하는 것

- `SharedSyncController` — 편집 메시지 디스패치(STOMP `@MessageMapping` + raw WS 양쪽)
- `<endpoint>/schema.proto` — 클라이언트가 코드 생성에 쓸 wire 스키마와 해시
- 프레즌스 등록·브로드캐스트, undo/redo 히스토리, Redis 팬아웃, ID Pool
- MeterRegistry 가 있으면 `sharedsync.frames`, `sharedsync.ws.sessions`,
  `sharedsync.errors{code}` 등 메트릭 (없으면 no-op)

---

## 기술 스택 (Tech Stack)
- **Language:** Java 17
- **Framework:** Spring Boot 3.x
- **Communication:** Spring WebSocket — STOMP+JSON 또는 raw WebSocket+protobuf (설정으로 전환)
- **Cache/Sync:** Redis (Lettuce), Spring Data Redis
- **Build Tool:** Gradle

---

## 라이선스 (License)
이 프로젝트는 [LICENSE](LICENSE) 파일에 정의된 라이선스를 따릅니다.

