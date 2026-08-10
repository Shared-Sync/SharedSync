# SharedSync wire 프로토콜 (raw WebSocket + protobuf)

`sharedsync.websocket.transport=websocket` 일 때의 클라이언트 계약이다.
STOMP+JSON 경로(기본값)는 이 문서의 대상이 아니다.

---

## 1. 스키마 받기

서버가 `<endpoint>/schema.proto` 로 생성된 `.proto` 텍스트를 서빙한다 (기본 경로는 WebSocket
endpoint 하위 — 앱의 시큐리티 화이트리스트가 핸드셰이크 경로를 이미 열어두기 때문이다).

```
GET /ws/schema.proto
200 OK
Content-Type: text/plain;charset=UTF-8
X-SharedSync-Schema-Hash: bd10658a6fc4ea46
ETag: "bd10658a6fc4ea46"
```

이 **응답 바이트 그대로** 를 `buf generate` / `protoc` 에 넣어 코드를 생성한다.

> 해시는 디스크립터가 아니라 `.proto` **텍스트**의 SHA-256 앞 8바이트다. 서버는 손으로 조립한
> FileDescriptorProto 를, 클라이언트는 protoc 산출 디스크립터를 갖게 되는데 이 둘은 같은 스키마의
> 서로 다른 인코딩이라 바이트가 일치하지 않는다. 텍스트는 양쪽이 같은 값을 해시한다.

빌드에 해시를 박아두고(`X-SharedSync-Schema-Hash` 또는 ETag), Join 프레임에 그대로 싣는다.

---

## 2. 연결

```js
const ws = new WebSocket(url, ["sharedsync.v1", "bearer." + accessToken]);
ws.binaryType = "arraybuffer";
```

토큰 전달 경로는 세 가지이고, 브라우저에서는 두 번째만 쓸 수 있다:

| 방법 | 용도 |
| :--- | :--- |
| `Authorization: Bearer <token>` 헤더 | 서버 대 서버 |
| `Sec-WebSocket-Protocol: sharedsync.v1, bearer.<token>` | **브라우저 권장** |
| `?token=<token>` 쿼리 | 하위 호환. URL 은 액세스 로그에 남으므로 피할 것 |

서버는 제시된 프로토콜 중 `sharedsync.v1` 을 골라 응답한다. 토큰 항목은 읽기만 하고 되돌려주지
않는다.

모든 프레임은 **바이너리**다. SockJS 폴백은 없다 — 텍스트 프레임만 보낼 수 있어 바이너리 wire 와
양립하지 않는다. 프록시(nginx/ingress)에 WebSocket upgrade 설정이 반드시 있어야 한다.

---

## 3. 프레임

인바운드는 `ClientFrame`, 아웃바운드는 `ServerFrame` 하나로 통일돼 있고 내용은 oneof 다.

```
ClientFrame  = Join | SyncRequest | Ping
ServerFrame  = Hello | SyncEvent | PresenceEvent | Pong | Error
```

### 3.1 순서

```
클라이언트                         서버
    |  --- (핸드셰이크) --------->  |
    |  <-------------- Hello ----- |   schema_hash
    |  --- Join ----------------->  |   room_id + schema_hash
    |  <----------- PresenceEvent-  |   현재 접속자 스냅샷
    |  --- SyncRequest ---------->  |   편집
    |  <------------- SyncEvent --  |   같은 룸 전원에게 브로드캐스트(본인 포함)
```

`Join` 이전에 보낸 편집은 `NOT_JOINED` 로 거절된다. 재연결하면 Join 부터 다시 한다.

### 3.2 Join

```protobuf
message Join {
  string room_id = 1;
  string schema_hash = 2;  // 서버 Hello 의 값과 같아야 한다
}
```

### 3.3 편집 (SyncRequest / SyncEvent)

`action` 이 `SYNC_ACTION_CREATE|UPDATE|DELETE` 면 payload oneof 에 해당 엔티티 리스트를 담는다.
`UNDO|REDO` 는 payload 없이 action 만 보낸다.

응답 `SyncEvent` 는 일반 편집과 undo/redo 가 **같은 모양**이고 `is_undo_redo` 로만 구분된다.

> **부분 업데이트**: 보내지 않은 필드는 서버에서 보존된다. proto3 `optional` 의 명시적 presence 가
> 그 근거이므로, 값을 지우려는 게 아니라면 필드를 **설정하지 말 것**. 빈 문자열/0 을 넣으면 그
> 값으로 덮인다.

`create` 시 ID 는 비워 보낸다. 서버가 배정한 ID 가 브로드캐스트에 실려 돌아온다.

### 3.4 프레즌스

입장·퇴장 때마다 `PresenceEvent` 가 룸 전체에 나가고, Join 직후에는 그 세션에만 현재 스냅샷이
따로 간다. `user_info` 는 `map<string,string>` 이다 — 노출 필드가 앱마다 달라 타입을 고정할 수 없다.

### 3.5 하트비트

- 서버 → 클라: WebSocket **ping 프레임**(기본 25초). 브라우저가 자동으로 pong 한다.
- 클라 → 서버: 필요하면 `Ping` 프레임을 보낸다. 서버는 `Pong` 으로 답한다. 이 응답은 편집 큐와
  무관하게 즉시 나간다.

---

## 4. 에러 코드

`Error{code, message}` 로 온다. 재시도해도 되는 것과 아닌 것이 섞여 있다.

| code | 서버 동작 | 클라이언트가 할 일 |
| :--- | :--- | :--- |
| `SCHEMA_MISMATCH` | **연결 종료** | 스키마를 다시 받아 코드 재생성. 재연결만 반복하면 계속 끊긴다 |
| `UNAUTHENTICATED` | **연결 종료** | 토큰 갱신 후 재연결 |
| `ACCESS_DENIED` | **연결 종료** | 재시도 무의미 (룸 권한 없음) |
| `NOT_JOINED` | 유지 | Join 후 재전송 |
| `BACKPRESSURE` | 유지 | **이 프레임은 처리되지 않았다.** 잠시 후 재전송 |
| `MALFORMED_FRAME` | 유지 | 스키마 스큐일 가능성이 높다. 스키마 재확인 |
| `UNKNOWN_FRAME` | 유지 | 서버가 모르는 arm — 서버가 구버전이다 |
| `INVALID_JOIN` | 유지 | room_id 확인 |
| `EDIT_FAILED` | 유지 | 같은 요청은 같은 결과일 가능성이 높다. 사용자에게 알릴 것 |
| `INTERNAL_ERROR` | 유지 | 재시도 |

서버 쪽에서는 `sharedsync.errors{code=...}` 카운터로 집계된다.

---

## 5. 백프레셔

세션마다 처리 큐가 있고(기본 200) 넘치면 `BACKPRESSURE` 를 돌려준다. 무한 큐를 두면 힙이 먼저
죽고, 조용히 버리면 클라이언트가 편집이 반영된 줄 안다. 그래서 **거절해서 알린다** — 클라이언트는
이 코드를 반드시 처리해야 한다.

같은 세션의 프레임은 보낸 순서대로 처리된다(세션 간에는 병렬). create 직후의 update 가 뒤집혀
실행되지 않는다는 뜻이다.

---

## 6. 크기 제한

인바운드 프레임 상한은 기본 256KB(`sharedsync.websocket.max-frame-size`). 넘는 편집은 나눠 보낸다 —
서버는 부분 메시지를 조립하지 않는다.
