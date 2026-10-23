# FiguraSVC — 내부판 (Fabric 1.21.8 · 스레드 안전 수정)

원본: `github.com/KnownSH/FiguraSVC` (= `FiguraSVC-v2`, 이름만 바뀐 같은 저장소) — **`1.21.4` 브랜치 `9d4b743`** 을 2026-09-30 에 받았다.
라이선스 LGPL-2.1 (`LICENSE`). GitHub `kwangsoo-assemble/MCmod_FiguraSVC_quadaFork`(공개 · 2026-10-12) — 이 작업본을 그대로 올린다
(하네스 `projects/mc_repositories/tools/repos.py publish figurasvc` 로만 · README 한글 원본은 하네스 `readme_ko/`).
업스트림을 다시 볼 때: `git fetch https://github.com/KnownSH/FiguraSVC 1.21.4`

Simple Voice Chat ↔ Figura 연동 애드온이다. 아바타 Lua 에 주는 것:

| 이름 | 언제 | 어느 스레드에서 도나 (이 판) |
|---|---|---|
| `events.MICROPHONE(name, pcm)` | 다른 플레이어의 음성이 들어올 때 — **그 사람의** 아바타 | 메인 스레드 (넘겨서) |
| `events.HOST_MICROPHONE(pcm)` → 바꾼 `pcm` | 내가 말할 때 — 내 아바타. 돌려준 표로 **내 음성을 바꾼다** | 음성 스레드 (잠금 안에서) |
| `events["svc.microphone"](pcm)` | 옛 이름 — 내가 말할 때 | 메인 스레드 (넘겨서) |
| `svc.amplify(pcm, n)` | Lua 에서 부르는 API | 부른 쪽 |

## ★★ 이 판에서 바꾼 것 — 음성 스레드가 아바타 Lua 를 동시에 돌리지 않게 (2026-09-30)

**증상** (전부 같은 원인이다)
- `…:<require 가 있는 줄> vm error: java.lang.ArrayIndexOutOfBoundsException: Index N out of bounds for length M` —
  마도사졸업시험 탤런트 `darkhold/ascension:1` · 2026-06 `svcManager:90`
- 블록벤치 애니메이션에 **수식**(키프레임 식)으로 pos · rot 을 준 아바타에서 가끔 나는 오류 (🙋 제보)

**원인** — 업스트림은 음성 채팅 스레드에서 곧장 `avatar.run(...)` 을 불렀다.
1. 같은 아바타의 Lua 를 렌더 스레드와 **동시에** 돌린다 → LuaJ 의 디버그 호출 기록(`DebugLib.CallStack`)이 두 스레드의
   호출 · 반환으로 뒤섞인다 → 그 뒤 Figura `require`(호출한 스크립트 폴더를 찾으려고 `debug.getinfo` 를 부른다)가
   `DebugLib.getfuncname` 의 `p.code[pc]` 에서 범위를 벗어난다. 키프레임 식도 메인 스레드에서 도는 Lua 라 같이 부딪힌다.
2. `Avatar.run` 은 먼저 **큐에 쌓인 핑 · 아바타 초기화**를 비운다 → 그것들이 음성 스레드에서 돈다.
   (`sync_pings` 가 켜져 있으면 내 핑도 서버에서 돌아와 큐에 쌓인다 — 말하는 동안 자주 넘어간다)
3. 옛 `svc.microphone` 경로는 **핸들러가 없어도** 음성 조각마다(약 20ms) `avatar.run` 을 불렀다 — 마이크 이벤트를 안 쓰는
   아바타도 말하는 동안 위 1 · 2 에 그대로 노출됐다. `luaRuntime` null 검사도 없었다.

**고친 것**
| 파일 | 무엇 |
|---|---|
| `svc/SVCPlugin.java` | MICROPHONE — 핸들러가 있을 때만 · PCM 을 복사해 `Minecraft.execute` 로 메인 스레드에 넘긴다 ·<br>HOST_MICROPHONE — 결과가 필요해 음성 스레드에 남기되 **아바타 잠금**(`tryLock` 5ms · 못 잡으면 그 조각은 원음)을 잡고 `Avatar.run` 대신 `runtime.run` (큐를 안 비운다) |
| `legacy/VoiceChatEventLegacy.java` | 음소거 · 아바타 · **핸들러 유무**를 먼저 보고, 메인 스레드로 넘긴다. PCM 표는 옛 판 그대로 0 부터 |
| `mixin/FiguraLuaRuntimeMixin.java` (새) | `FiguraLuaRuntime.run` · `init` 을 아바타별 `ReentrantLock` 으로 감싼다 (`@WrapMethod`) — Lua 가 도는 입구가 이 둘뿐이다 |
| `LuaLockAccessor.java` (새) | 그 잠금을 꺼내는 인터페이스 |
| 공통 | 아바타는 `AvatarManager.getLoadedAvatar` 로 찾는다 — `getAvatarForPlayer` 는 백엔드 fetch 부작용이 있어 음성 패킷마다 부르면 안 된다 |

## 빌드

- `JAVA_HOME="C:\Program Files\Java\jdk-21" ./gradlew build` → `build/libs/figurasvc-2.0.0.2+1.21.8.jar`
- 도구 사슬은 Chat-Heads 내부판과 같다 — Loom `1.17-SNAPSHOT`(`net.fabricmc.fabric-loom-remap`) + Gradle 9.5.1 · 공식 매핑
- 업스트림의 멀티로더(architectury · forge · neoforge)는 걷었다 — 이 환경은 Fabric 1.21.8 뿐이다 (첫 커밋 `26cf97a` 에 동작 변경 없음)

## 의존

- **Figura**: `.libs/figura-0.1.6.4+1.21.8-fabric-mc.jar` — `../figura_core_internal` 산출물 사본(git 제외 · 컴파일 전용).
  포크의 `FiguraLuaRuntime.run/init` · `EventsAPI.events` 시그니처가 바뀌면 다시 복사하고 다시 빌드한다
- **LuaJ**: `com.github.FiguraMC.luaj:luaj-core:3.0.8-figura` (jitpack) — 코어 jar 안에는 중첩 jar 로만 있어 따로 건다
- **Simple Voice Chat API**: `de.maxhenkel.voicechat:voicechat-api:2.6.0`
- **MixinExtras** `@WrapMethod` (0.4+) — Fabric Loader 0.16+ 가 들고 있다 (탤런트 모드팩 Loader 0.19.3 · 같은 기능을 AsyncParticles 도 쓴다)

## 배포

- 하네스에서 `python projects/_shared/deploy_mod_jars.py --dry-run` → 게임을 끈 채 실행 (Modrinth 프로필 두 곳).
  옛 `figurasvc-*.jar` 는 `.disabled_<ts>` 로 치운다 (두 판이 같이 있으면 Fabric 이 중복 모드로 죽는다)
- 🙋 **탤런트 모드팩(.mrpack)은 다시 묶어야 한다** — 배포 도구는 이 PC 의 프로필만 바꾼다

## 검증 · 한계

- ⚠ `defaultRequire: 1` — 믹스인 대상(`run` · `init`)을 못 찾으면 **게임이 시작할 때 멈춘다.** 포크를 바꿨으면 `javap -p -s` 로
  `FiguraLuaRuntime` 의 `run(Object, Avatar$Instructions, Object[])` · `init(class_2499)` 를 먼저 본다 (2026-09-30 두 포크 jar 모두 일치)
- HOST_MICROPHONE 핸들러는 여전히 음성 스레드에서 돈다 (음성을 그 조각에 바꿔야 해서). 그 안에서 **다른 아바타의 함수**를 부르면
  그 아바타는 잠금 밖이다 — 그런 아바타는 없다(2026-09-30)
- PCM 표 인덱스가 업스트림부터 어긋나 있다 — 넘길 때 0 부터(`pcmLuaEncode`), 받을 때 1 부터(`pcmLuaDecode`). 옛 아바타 호환이라 **그대로 둔다**
- 오프라인으로는 음성 경로를 못 돌린다 — 확인은 인게임: 음성 채팅으로 말하는 동안 아바타 리로드 · 핑 · 키프레임 수식 아바타에서 오류가 사라지는지
