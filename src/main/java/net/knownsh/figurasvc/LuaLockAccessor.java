package net.knownsh.figurasvc;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 아바타(FiguraLuaRuntime) 하나에 붙는 Lua 실행 잠금 — {@link net.knownsh.figurasvc.mixin.FiguraLuaRuntimeMixin} 이 붙인다.
 *
 * ★ 왜 필요한가 — LuaJ 는 한 런타임을 두 스레드가 동시에 돌리는 것을 견디지 못한다.
 *   디버그 호출 기록(DebugLib CallStack)이 두 스레드의 호출·반환으로 뒤섞여, 그 뒤 `require` 가 부르는
 *   `debug.getinfo` 가 «Index N out of bounds for length M» 으로 죽는다 (2026-09-30 실측 — 마도사졸업시험 탤런트).
 *   메인 스레드의 모든 실행과 음성 스레드의 HOST_MICROPHONE 실행이 이 잠금 하나를 나눠 쓴다.
 */
public interface LuaLockAccessor {
    ReentrantLock FiguraSVC$getLuaLock();
}
