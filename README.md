# MCmod_FiguraSVC_quadaFork

**[한국어](#한국어)** 설명은 아래에 있습니다.

> An **AI-assisted fork** of the Figura add-on [FiguraSVC](https://github.com/KnownSH/FiguraSVC) (Simple Voice Chat integration).
> It moves the mod to a single **Minecraft 1.21.8 (Fabric)** build (against
> [MCmod_Figura_quadaFork](https://github.com/kwangsoo-assemble/MCmod_Figura_quadaFork)) and fixes errors caused by the
> voice-chat thread running avatar Lua **at the same time** as the render thread.

> ⚠ **This is an unofficial fork** and is not affiliated with the original author. Most changes were written with an
> AI coding assistant (Claude Code) and checked by the maintainer in real use. Before reporting a problem upstream,
> please make sure it is not caused by this fork.

## At a glance

| Item | Value |
|---|---|
| Version | `2.0.0.2` — upstream 2.0 padded to `2.0.0`, plus a fourth number for this fork's own release (first release: tag `2.0-but-ai-edited.1`) |
| Based on | upstream branch `1.21.4` at `9d4b743` |
| Target | Minecraft 1.21.8 · Fabric — upstream's multi-loader setup (architectury · Forge · NeoForge) was removed |
| Figura | MCmod_Figura_quadaFork `0.1.6.4+1.21.8` (not on the official Maven) |
| Requires | Simple Voice Chat API `2.6.0` · Fabric Loader 0.16+ (MixinExtras `@WrapMethod`) |
| Java | 21 |

## What it gives avatar Lua

| Name | When | Thread (this fork) |
|---|---|---|
| `events.MICROPHONE(name, pcm)` | another player's voice arrives — on **that player's** avatar | main thread (handed over) |
| `events.HOST_MICROPHONE(pcm)` → modified `pcm` | you speak — your avatar. The returned table **changes your voice** | voice thread (inside a lock) |
| `events["svc.microphone"](pcm)` | legacy name — you speak | main thread (handed over) |
| `svc.amplify(pcm, n)` | API called from Lua | caller's thread |

## Changes from upstream

### 1. Single Fabric 1.21.8 build (`26cf97a` — no behavior change)

- Loom `1.17-SNAPSHOT` (`net.fabricmc.fabric-loom-remap`) + Gradle 9.5.1 · official mappings
- Figura is referenced from the **MCmod_Figura_quadaFork build jar** in `.libs/` instead of the official Maven (see «Building»)

### 2. The voice thread no longer runs avatar Lua concurrently (`fce6866`)

**Symptoms** — both have the same cause.

- `…:<line with require> vm error: java.lang.ArrayIndexOutOfBoundsException: Index N out of bounds for length M`
- Occasional errors on avatars whose Blockbench animation keyframes use **expressions** for pos · rot

**Cause** — upstream called `avatar.run(...)` directly from the voice-chat thread.

1. The same avatar's Lua runs **concurrently** with the render thread → LuaJ's debug call stack (`DebugLib.CallStack`) gets
   mixed with calls and returns from both threads → Figura's `require` (which calls `debug.getinfo` to find the calling
   script's folder) then goes out of bounds in `DebugLib.getfuncname`. Keyframe expressions are Lua on the main thread, so they collide too.
2. `Avatar.run` first drains the **queued pings and avatar init** → those then run on the voice thread.
3. The legacy `svc.microphone` path called `avatar.run` for every voice frame (~20 ms) **even with no handler** — avatars that
   never use microphone events were still exposed to 1 and 2 while their owner was talking.

**Fixes**

| File | What |
|---|---|
| `svc/SVCPlugin.java` | MICROPHONE — only when a handler exists · copies the PCM and hands it to the main thread (`Minecraft.execute`) · HOST_MICROPHONE — needs a result, so it stays on the voice thread but takes an **avatar lock** (`tryLock` 5 ms · on failure that frame stays unmodified) and calls `runtime.run` instead of `Avatar.run` (does not drain the queue) |
| `legacy/VoiceChatEventLegacy.java` | checks mute · avatar · **whether a handler exists** first, then hands over to the main thread |
| `mixin/FiguraLuaRuntimeMixin.java` (new) | wraps `FiguraLuaRuntime.run` · `init` with a per-avatar `ReentrantLock` (`@WrapMethod`) — these are the only two entry points where Lua runs |
| `LuaLockAccessor.java` (new) | interface to get that lock |
| common | avatars are looked up with `AvatarManager.getLoadedAvatar` — `getAvatarForPlayer` has a backend-fetch side effect and must not be called per voice packet |

## Building

```bash
# 1) Build MCmod_Figura_quadaFork and put its jar in .libs/
#    .libs/figura-0.1.6.4+1.21.8-fabric-mc.jar
# 2) Build with JDK 21
./gradlew build
# → build/libs/figurasvc-2.0.0.2+1.21.8.jar
```

- LuaJ (`com.github.FiguraMC.luaj:luaj-core:3.0.8-figura`) only exists as a nested jar inside the core jar, so it is added separately (jitpack)
- If the core's `FiguraLuaRuntime.run/init` · `EventsAPI.events` signatures change, copy the `.libs/` jar again and rebuild

## License

**LGPL-2.1**, the same as upstream (`LICENSE`). The upstream README is kept as `README.upstream.md`.

---

# 한국어

> Figura 애드온 [FiguraSVC](https://github.com/KnownSH/FiguraSVC)(Simple Voice Chat 연동)를 **AI 도구로 수정한 포크**다.
> Minecraft **1.21.8 (Fabric)** 단일 빌드로 옮기고(대상 Figura 는 [MCmod_Figura_quadaFork](https://github.com/kwangsoo-assemble/MCmod_Figura_quadaFork)),
> 음성 채팅 스레드가 아바타 Lua 를 렌더 스레드와 **동시에** 돌려 나던 오류를 고쳤다.

> ⚠ **비공식 포크다.** 원본 제작자와 관련이 없다. 수정의 대부분은 AI 코딩 도구(Claude Code)로 작성했고
> 유지보수자가 실제 사용으로 확인했다. 원본에 문제를 알리기 전에 이 포크 때문인지 먼저 확인해 달라.

## 한눈에

| 항목 | 값 |
|---|---|
| 버전 | `2.0.0.2` — 업스트림 2.0 을 셋째 자리까지 채운 `2.0.0` 뒤 넷째 자리가 이 포크의 판 번호(첫 판은 꼬리 `2.0-but-ai-edited.1`) |
| 기준 | 업스트림 `1.21.4` 브랜치 `9d4b743` |
| 타깃 | Minecraft 1.21.8 · Fabric — 업스트림의 멀티로더(architectury · Forge · NeoForge)는 걷었다 |
| 대상 Figura | MCmod_Figura_quadaFork `0.1.6.4+1.21.8` (공식 Maven 에 없다) |
| 의존 | Simple Voice Chat API `2.6.0` · Fabric Loader 0.16+ (MixinExtras `@WrapMethod`) |
| Java | 21 |

## 아바타 Lua 에 주는 것

| 이름 | 언제 | 어느 스레드에서 도나 (이 포크) |
|---|---|---|
| `events.MICROPHONE(name, pcm)` | 다른 플레이어의 음성이 들어올 때 — **그 사람의** 아바타 | 메인 스레드 (넘겨서) |
| `events.HOST_MICROPHONE(pcm)` → 바꾼 `pcm` | 내가 말할 때 — 내 아바타. 돌려준 표로 **내 음성을 바꾼다** | 음성 스레드 (잠금 안에서) |
| `events["svc.microphone"](pcm)` | 옛 이름 — 내가 말할 때 | 메인 스레드 (넘겨서) |
| `svc.amplify(pcm, n)` | Lua 에서 부르는 API | 부른 쪽 |

## 원본 대비 변경 사항

### 1. Fabric 1.21.8 단일 빌드 (`26cf97a` — 동작 변경 없음)

- Loom `1.17-SNAPSHOT`(`net.fabricmc.fabric-loom-remap`) + Gradle 9.5.1 · 공식 매핑
- Figura 는 공식 Maven 대신 **MCmod_Figura_quadaFork 의 빌드 jar** 를 `.libs/` 에서 참조한다 (아래 «빌드»)

### 2. 음성 스레드가 아바타 Lua 를 동시에 돌리지 않게 (`fce6866`)

**증상** — 둘 다 같은 원인이다.

- `…:<require 가 있는 줄> vm error: java.lang.ArrayIndexOutOfBoundsException: Index N out of bounds for length M`
- 블록벤치 애니메이션 키프레임에 **수식**으로 pos · rot 을 준 아바타에서 가끔 나는 오류

**원인** — 업스트림은 음성 채팅 스레드에서 곧장 `avatar.run(...)` 을 불렀다.

1. 같은 아바타의 Lua 를 렌더 스레드와 **동시에** 돌린다 → LuaJ 의 디버그 호출 기록(`DebugLib.CallStack`)이 두 스레드의
   호출 · 반환으로 뒤섞인다 → 그 뒤 Figura `require`(호출한 스크립트 폴더를 찾으려고 `debug.getinfo` 를 부른다)가
   `DebugLib.getfuncname` 에서 범위를 벗어난다. 키프레임 식도 메인 스레드에서 도는 Lua 라 같이 부딪힌다.
2. `Avatar.run` 은 먼저 **큐에 쌓인 핑 · 아바타 초기화**를 비운다 → 그것들이 음성 스레드에서 돈다.
3. 옛 `svc.microphone` 경로는 **핸들러가 없어도** 음성 조각마다(약 20ms) `avatar.run` 을 불렀다 — 마이크 이벤트를 안 쓰는
   아바타도 말하는 동안 위 1 · 2 에 그대로 노출됐다.

**고친 것**

| 파일 | 무엇 |
|---|---|
| `svc/SVCPlugin.java` | MICROPHONE — 핸들러가 있을 때만 · PCM 을 복사해 메인 스레드(`Minecraft.execute`)로 넘긴다 · HOST_MICROPHONE — 결과가 필요해 음성 스레드에 남기되 **아바타 잠금**(`tryLock` 5ms · 못 잡으면 그 조각은 원음)을 잡고 `Avatar.run` 대신 `runtime.run` (큐를 안 비운다) |
| `legacy/VoiceChatEventLegacy.java` | 음소거 · 아바타 · **핸들러 유무**를 먼저 보고 메인 스레드로 넘긴다 |
| `mixin/FiguraLuaRuntimeMixin.java` (새) | `FiguraLuaRuntime.run` · `init` 을 아바타별 `ReentrantLock` 으로 감싼다(`@WrapMethod`) — Lua 가 도는 입구가 이 둘뿐이다 |
| `LuaLockAccessor.java` (새) | 그 잠금을 꺼내는 인터페이스 |
| 공통 | 아바타는 `AvatarManager.getLoadedAvatar` 로 찾는다 — `getAvatarForPlayer` 는 백엔드 조회 부작용이 있어 음성 패킷마다 부르면 안 된다 |

## 빌드

```bash
# 1) MCmod_Figura_quadaFork 를 빌드해 그 jar 를 .libs/ 에 둔다
#    .libs/figura-0.1.6.4+1.21.8-fabric-mc.jar
# 2) JDK 21 로 빌드
./gradlew build
# → build/libs/figurasvc-2.0.0.2+1.21.8.jar
```

- LuaJ(`com.github.FiguraMC.luaj:luaj-core:3.0.8-figura`)는 코어 jar 안에 중첩 jar 로만 있어 따로 건다(jitpack)
- 코어의 `FiguraLuaRuntime.run/init` · `EventsAPI.events` 시그니처가 바뀌면 `.libs/` jar 를 다시 복사하고 다시 빌드한다

## 라이선스

업스트림과 같은 **LGPL-2.1** (`LICENSE`). 업스트림 README 는 `README.upstream.md` 로 남겼다.
