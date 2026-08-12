# 클라이언트 붙이기

프론트엔드/앱에서 SharedSync 서버에 연결하는 방법. 서버 쪽 설정은 [README](../README.md),
프레임 단위 계약은 [wire-protocol.md](wire-protocol.md)를 본다.

---

## 1. 무엇을 고를 것인가

| | STOMP + JSON | raw WebSocket + protobuf |
| :--- | :--- | :--- |
| 서버 설정 | `transport: stomp` (기본) | `transport: websocket` |
| 클라이언트 | `@stomp/stompjs` + SockJS | 표준 `WebSocket` + protobuf 런타임 |
| 프레임 크기 | 기준 | 대략 1/3 (실측: 355B → 113B) |
| 폴백 | SockJS(xhr 등) | **없음** — 프록시가 upgrade 를 통과시켜야 한다 |
| 스키마 계약 | 없음(자유 JSON) | 있음(해시로 스큐 검출) |

`transport: both` 로 두면 서버가 둘을 **동시에** 서비스한다. 웹과 앱의 배포 시점이 다르고 앱은
사용자가 업데이트해야 하므로, 실제 전환은 사실상 이 모드를 거친다. 이때 raw WS 는 별도 경로
(`<endpoint>/v2`)로 뜬다 — SockJS 가 `<endpoint>/**` 를 통째로 잡기 때문이다.

---

## 2. 기존 코드를 거의 안 바꾸는 방법

STOMP 클라이언트를 쓰던 앱이라면, 호출부는 대개 이 두 가지만 쓴다:

```js
const client = getClient();
if (client.connected) client.publish({ destination: `/app/${roomId}`, body: JSON.stringify(msg) });
```

그렇다면 **같은 표면(`connected` / `active` / `publish` / `deactivate`)을 노출하는 어댑터**를 만들면
호출부는 한 줄도 바뀌지 않는다.

```js
const adapter = {
  connected: false,
  active: true,
  publish({ body }) {
    const msg = JSON.parse(body);       // 호출부가 만든 그 JSON
    ws.send(toClientFrame(msg));        // protobuf 바이트로
  },
  deactivate() { /* ... */ },
};
```

수신도 같다. 기존 핸들러가 `{ eventId, action, entity, <entity>Dtos, isUndoRedo }` 모양의 객체를
받고 있었다면, `ServerFrame` 을 디코딩해 **같은 모양**으로 만들어 넘기면 핸들러도 그대로다.

> 참조 구현: planmate 웹의 `frontend/src/websocket/protoClient.js`.
> 이 방식으로 호출부 5개 파일이 0줄, 빌드 설정 0줄로 전환됐다.

---

## 3. 스키마 가져오기

### 런타임 파싱 (권장 — 전환기 동안)

서버가 `<endpoint>/schema.proto` 로 생성된 `.proto` 텍스트를 서빙한다. 코드 생성 도구가 필요 없다.

```js
import protobuf from "protobufjs";

const response = await fetch(`${baseUrl}/ws/schema.proto`);
const text = await response.text();
const hash = response.headers.get("X-SharedSync-Schema-Hash");

const parsed = protobuf.parse(text);   // keepCase=false → 필드명이 camelCase 로 온다
parsed.root.resolveAll();              // ★ 빼면 field.resolvedType 이 전부 null 이라 enum 처리가 죽는다
const type = (n) => parsed.root.lookupType(`${parsed.package}.${n}`);
```

빌드 파이프라인을 건드리지 않고, 서버에 엔티티가 추가돼도 클라이언트 코드가 바뀌지 않는다.
대신 연결 전에 요청이 한 번 더 생긴다(3~4KB, ETag 로 재검증 가능).

### 번들 (안정화 이후)

`.proto` 를 리포에 넣고 `buf generate` 로 코드를 만들거나 텍스트를 번들에 포함시킨다.
정적 타입이 생기고 런타임 요청이 사라지는 대신, 스키마가 바뀌면 클라이언트 재배포가 필요하다.
번들할 때는 **해시를 직접 계산**해야 한다(`SubtleCrypto` 로 텍스트의 SHA-256 앞 8바이트).

### 엔티티 목록도 스키마에서 유도한다

하드코딩하지 않는 편이 좋다. `SyncRequest` 의 `payload` oneof 에 있는 `<Entity>List` 들이 그대로
엔티티 집합이다:

```js
for (const field of type("SyncRequest").oneofsArray.find(o => o.name === "payload").fieldsArray) {
  const listType = field.resolvedType;
  const entityName = listType.name.replace(/List$/, "");        // TimeTablePlaceBlockList → TimeTablePlaceBlock
  const camel = entityName[0].toLowerCase() + entityName.slice(1);
  entities[entityName.toLowerCase()] = {
    arm: field.name,                    // oneof 필드명 (timeTablePlaceBlocks)
    dtoKey: `${camel}Dtos`,             // 기존 JSON wire 의 리스트 필드명
    itemType: listType.fields.items.resolvedType,
  };
}
```

---

## 4. 연결과 Join

```js
const ws = new WebSocket(`${base}/ws/v2`, ["sharedsync.v1", `bearer.${token}`]);
ws.binaryType = "arraybuffer";
```

토큰은 세 곳에서 받는다. 브라우저는 핸드셰이크 헤더를 지정할 수 없으므로 **서브프로토콜**을 쓴다.
쿼리 파라미터(`?token=`)도 아직 동작하지만 URL 은 액세스 로그에 그대로 남는다 — JWT 가 로그
파일에 적힌다는 뜻이라 권장하지 않는다.

연결되면 서버가 `Hello{schema_hash}` 를 먼저 보낸다. 그 뒤에 `Join` 을 보낸다:

```js
if (frame.frame === "hello") {
  send({ join: { roomId, schemaHash: mySchemaHash } });   // ★ 내 해시
}
```

> **서버가 준 해시를 그대로 되돌려주면 안 된다.** 그러면 검사가 항상 통과해 스키마 스큐 검출이
> 무력해진다 — 클라이언트가 캐시한 옛 스키마로 인코딩하는 상황이 바로 그 검사가 막으려던 것이다.
> 반드시 **자신이 인코딩에 쓴 스키마**의 해시를 실어야 한다.

`Join` 이전에 보낸 편집은 `NOT_JOINED` 로 거절된다. 재연결하면 Join 부터 다시 한다.

---

## 5. 송신

```js
function publish({ body }) {
  const msg = JSON.parse(body);
  const action = msg.action.toLowerCase();

  const request = { eventId: msg.eventId ?? "", action: `SYNC_ACTION_${action.toUpperCase()}` };

  // undo/redo 는 페이로드 없이 action 만 보낸다
  if (action !== "undo" && action !== "redo") {
    const entity = entities[msg.entity.toLowerCase()];
    request[entity.arm] = { items: msg[entity.dtoKey].map(d => encodeDto(entity.itemType, d)) };
  }
  ws.send(ClientFrame.encode(ClientFrame.fromObject({ sync: request })).finish());
}
```

`create` 시 ID 는 비워 보낸다. 서버가 배정한 ID 가 브로드캐스트에 실려 돌아온다.

---

## 6. 타입 왕복에서 반드시 다뤄야 하는 것 4가지

이 넷을 빼먹으면 **조용히** 잘못 동작한다.

### ① 설정하지 않은 필드는 서버가 보존한다

부분 업데이트의 근거다. 값을 지우려는 게 아니라면 **필드를 아예 설정하지 말 것.**
`undefined`/`null` 을 넣지 말고 건너뛴다 — 빈 문자열이나 `0` 을 넣으면 그 값으로 덮인다.

```js
for (const field of type.fieldsArray) {
  const value = dto[field.name];
  if (value === undefined || value === null) continue;   // ★
  plain[field.name] = value;
}
```

### ② int64 는 Long 객체로 온다

`toObject(msg, { longs: Number })` 로 되돌리지 않으면 `blockId === 556` 같은 비교가 전부 실패한다.

### ③ BigDecimal 은 wire 에서 문자열이다

numeric 정밀도를 지키려고 문자열로 나간다(`latitude`, `longitude` 등). 앱이 숫자를 기대한다면
받을 때 `Number()` 로 되돌린다.

### ④ enum 에는 접두사가 붙는다

buf lint 규칙상 `BlockCategory.ATTRACTION` 은 wire 에서 `BLOCK_CATEGORY_ATTRACTION` 이다.
보낼 때 붙이고 받을 때 뗀다. 접두사는 enum 이름에서 유도할 수 있다:

```js
const enumPrefix = (name) => name.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toUpperCase();
```

---

## 7. 수신

`ServerFrame` 의 oneof 로 분기한다.

| arm | 내용 | 처리 |
| :--- | :--- | :--- |
| `hello` | `schema_hash` | Join 을 보낸다 |
| `sync` | 편집 브로드캐스트 | 기존 편집 핸들러로 (본인이 보낸 것도 돌아온다) |
| `presence` | 접속자 스냅샷/입퇴장 | 접속자 목록 갱신 |
| `error` | `{code, message}` | 아래 표 |
| `pong` | — | 무시 |

`sync` 는 일반 편집과 undo/redo 가 **같은 모양**이고 `is_undo_redo` 로만 구분된다.

---

## 8. 에러 코드 대응

| code | 클라이언트가 할 일 |
| :--- | :--- |
| `SCHEMA_MISMATCH` | 서버가 연결을 끊는다. **스키마를 다시 받아야 한다** — 그냥 재연결하면 같은 이유로 계속 끊긴다 |
| `UNAUTHENTICATED` | 토큰 갱신 후 재연결 |
| `ACCESS_DENIED` | 재시도 무의미(룸 권한 없음) |
| `NOT_JOINED` | Join 후 재전송 |
| `BACKPRESSURE` | **이 프레임은 처리되지 않았다.** 잠시 후 재전송. 무시하면 사용자는 편집이 반영된 줄 안다 |
| `MALFORMED_FRAME` / `UNKNOWN_FRAME` | 스키마 스큐 의심 |
| `EDIT_FAILED` / `INTERNAL_ERROR` | 사용자에게 알리거나 재시도 |

전체 목록과 서버 동작은 [wire-protocol.md §4](wire-protocol.md) 참고.

---

## 9. 재연결과 하트비트

- 끊기면 **Join 부터** 다시 한다. 스키마는 캐시해도 되지만, `SCHEMA_MISMATCH` 를 받으면 버린다.
- 서버가 주기적으로 WebSocket **ping 프레임**을 보낸다(기본 25초). 브라우저는 자동으로 pong 한다.
- 클라이언트 쪽에서 생존 확인이 필요하면 `Ping` 프레임을 보낸다. `Pong` 은 편집 큐와 무관하게
  즉시 돌아온다.

---

## 10. React Native 주의

같은 전략이 가능하지만 **`binaryType = "arraybuffer"` 지원 여부를 먼저 확인**해야 한다.
RN 의 WebSocket 구현은 브라우저와 다르다. 여기서 막히면 앱만 STOMP 에 남겨두면 된다 —
`transport: both` 가 그걸 허용한다.

TypeScript 앱이라면 런타임 파싱 대신 코드 생성(`buf generate`)이 값을 한다. 정적 타입이 생기고,
위 §6 의 변환 실수를 컴파일러가 잡아준다.
