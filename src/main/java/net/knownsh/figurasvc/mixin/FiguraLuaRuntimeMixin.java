package net.knownsh.figurasvc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.knownsh.figurasvc.LuaLockAccessor;
import net.minecraft.nbt.ListTag;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.lua.FiguraLuaRuntime;
import org.luaj.vm2.Varargs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 한 아바타의 Lua 는 한 번에 한 스레드만 돌린다.
 *
 * Lua 가 도는 입구는 둘뿐이다 — `run`(이벤트 · 핑 · 렌더 콜백 · 키프레임 식 · 서버 패킷이 전부 여기로 온다)과
 * `init`(아바타를 불러온 뒤 큐에서 한 번). 둘을 같은 잠금으로 감싼다.
 * ⓘ ReentrantLock — Lua 안에서 다시 run 이 불리는 중첩(소리 → ON_PLAY_SOUND 등)은 같은 스레드라 그대로 통과한다.
 * ⓘ 잠금은 메인 스레드끼리는 늘 비어 있어 비용이 거의 없다. 기다림이 생기는 것은 음성 스레드가 HOST_MICROPHONE 을
 *   돌리는 몇 ms 동안뿐이다 (SVCPlugin 쪽은 tryLock 으로 오래 기다리지 않는다).
 */
@Mixin(value = FiguraLuaRuntime.class, remap = false)
public abstract class FiguraLuaRuntimeMixin implements LuaLockAccessor {
    @Unique
    private final ReentrantLock FiguraSVC$luaLock = new ReentrantLock();

    @Override
    public ReentrantLock FiguraSVC$getLuaLock() {
        return FiguraSVC$luaLock;
    }

    @WrapMethod(method = "run")
    private Varargs FiguraSVC$lockRun(Object toRun, Avatar.Instructions limit, Object[] args, Operation<Varargs> original) {
        FiguraSVC$luaLock.lock();
        try {
            return original.call(toRun, limit, args);
        } finally {
            FiguraSVC$luaLock.unlock();
        }
    }

    @WrapMethod(method = "init")
    private boolean FiguraSVC$lockInit(ListTag autoScripts, Operation<Boolean> original) {
        FiguraSVC$luaLock.lock();
        try {
            return original.call(autoScripts);
        } finally {
            FiguraSVC$luaLock.unlock();
        }
    }
}
