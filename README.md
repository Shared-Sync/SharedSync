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

JitPack 으로 배포됩니다. 버전은 커밋 해시(10자) 또는 태그입니다.

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation "com.github.Shared-Sync.SharedSync:sharedsync-starter:$sharedSyncVersion"
    // 코드 생성(DTO/서비스/컨트롤러/wire 스키마)에 반드시 필요하다
    annotationProcessor "com.github.Shared-Sync.SharedSync:sharedsync-autoconfigure:$sharedSyncVersion"
}
```

멀티 모듈로 함께 빌드한다면 `project(':sharedsync-starter')` / `project(':sharedsync-autoconfigure')`
를 쓴다.

---

## 빠른 시작 (Quick Start)

1. 협업 대상 엔티티에 `@CacheEntity` + `@CacheId`, 방의 루트에 `@PresenceRoot`, 접속자 엔티티에
   `@PresenceUser` 를 붙인다 ([§사용 방법](#사용-방법-how-to-use))
2. `AuthenticationTokenResolver` 빈을 등록한다
3. `application.yml` 에 endpoint 를 지정한다

```yaml
sharedsync:
  websocket:
    endpoint: /ws
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}
  cache:
    type: redis
```

이걸로 STOMP + JSON 전송이 동작한다. 컨트롤러·DTO·서비스·저장소는 애노테이션 프로세서가 만든다.
protobuf 전송으로 넘어가려면 [§전송 계층 전환](#전송-계층-전환)을 본다.

---

## 환경 설정 (Environment Variables / Properties)

`application.yml` 또는 `application.properties`로 조정합니다.

### 전송 계층 (`sharedsync.websocket`)

| Property | 기본값 | 설명 |
| :--- | :--- | :--- |
| `transport` | `stomp` | `stomp`(현행) / `websocket`(raw WS + protobuf) / `both`(둘 동시 — 무중단 전환용) |
| `websocket-endpoint` | `<endpoint>/v2` | `both` 모드에서 raw WebSocket 이 뜰 경로 |
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

**protobuf 로 바꿀 때 앱이 할 일은 `transport` 한 줄이다.** codec 과 sockjs 는 프레임워크가
맞춘다(서로 성립하지 않는 조합이 있는데, 그걸 아는 것은 프레임워크의 몫이다).

전환은 `both` 로 한다. STOMP+JSON 과 raw WS+protobuf 가 **같은 룸을 공유**하므로, 구버전
클라이언트가 남아 있는 동안 새 클라이언트를 배포할 수 있다. 웹과 앱의 배포 시점이 다르고 앱은
사용자가 업데이트해야 하므로, "모든 클라이언트를 같은 순간에 바꾸기"는 실제로는 불가능한 요구다.
모두 옮겨간 뒤 `websocket` 으로 내리면 된다.

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

> **필드 번호는 `sharedsync-wire.lock` 이 정한다.** 빌드가 만들어주는 이 파일을
> `src/main/resources/` 에 커밋해두면, 잠긴 필드는 선언 순서를 바꿔도 번호가 움직이지 않고
> 제거된 필드의 번호는 `reserved` 로 남아 재사용되지 않는다. 새 필드는 그 엔티티가 쓴 적 있는
> 가장 큰 번호 다음을 받는다. **필드를 지우더라도 잠금 파일의 그 줄은 지우지 말 것** — 그게
> 번호를 붙잡아 두는 유일한 수단이고, 지우면 구 클라이언트가 보낸 값이 새 필드로 읽힌다.
>
> 잠금이 없으면(첫 빌드) 선언 순서로 매긴다.

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

### 3. 프레임워크가 제공하는 것

- `SharedSyncController` — 편집 메시지 디스패치(STOMP `@MessageMapping` + raw WS 양쪽)
- `<endpoint>/schema.proto` — 클라이언트가 코드 생성에 쓸 wire 스키마와 해시
- 프레즌스 등록·브로드캐스트, undo/redo 히스토리, Redis 팬아웃, ID Pool
- MeterRegistry 가 있으면 `sharedsync.frames`, `sharedsync.ws.sessions`,
  `sharedsync.errors{code}` 등 메트릭 (없으면 no-op)

---

## 클라이언트 붙이기

프론트엔드/앱에서 연결하는 방법은 **[docs/client-guide.md](docs/client-guide.md)** 에 있다.
어댑터로 감싸 기존 호출부를 그대로 두는 최소 변경 경로, 스키마 받기, 타입 왕복에서 반드시
다뤄야 하는 네 가지(미설정 필드 보존 / int64 / BigDecimal / enum 접두사), 에러 코드 대응을 다룬다.

프레임 단위 계약(순서, oneof 구성, 백프레셔, 크기 제한)은
**[docs/wire-protocol.md](docs/wire-protocol.md)** 를 본다.

STOMP + JSON 을 쓰는 동안에는 클라이언트에 특별한 계약이 없다. 목적지는 편집이
`/topic/{roomId}`, 프레즌스가 `/topic/{presenceChannel}/{roomId}`, 발행은 `/app/{roomId}` 다.

---

## 전송 계층 전환

`transport` 하나로 정한다. codec 과 SockJS 는 프레임워크가 맞춘다.

```
stomp        기본. STOMP + JSON. 지금까지의 동작.
  ↓
both         둘을 동시에 서비스한다. raw WS 는 <endpoint>/v2 로 뜬다.
  ↓          같은 룸을 공유하므로 구·신 클라이언트가 서로의 편집을 본다.
websocket    raw WebSocket + protobuf 만. 경로는 <endpoint> 로 돌아온다.
```

`both` 가 필요한 이유는 웹과 앱의 배포 시점이 다르기 때문이다. 앱은 사용자가 업데이트해야 하므로
"모든 클라이언트를 같은 순간에 바꾸기"는 실제로는 성립하지 않는다.

전환 전 확인할 것:

- **프록시가 WebSocket upgrade 를 통과시키는지.** SockJS 는 실패해도 폴백으로 조용히 동작하므로,
  "지금 잘 된다"가 upgrade 가 된다는 뜻이 아니다. 브라우저 DevTools 에서 `101 Switching Protocols`
  를 확인한다. `proxy_read_timeout` 은 ping 주기(기본 25초)보다 커야 한다.
- **allowed-origins 에 실제 프론트 도메인이 있는지.** raw WS 는 브라우저가 Origin 을 반드시
  보내므로, 목록이 어긋나면 핸드셰이크가 403 으로 막힌다.
- **필드 번호.** `sharedsync-wire.lock` 을 `src/main/resources/` 에 커밋해두면 필드 번호가 고정되고,
  이후 엔티티에서 필드를 지우거나 순서를 바꿔도 wire 가 깨지지 않는다. 잠금 파일의 줄은 지우지 말 것.

---

## 문서

| | |
| :--- | :--- |
| [docs/client-guide.md](docs/client-guide.md) | 프론트엔드/앱 연결 가이드 |
| [docs/wire-protocol.md](docs/wire-protocol.md) | raw WebSocket + protobuf 프레임 계약 |

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

