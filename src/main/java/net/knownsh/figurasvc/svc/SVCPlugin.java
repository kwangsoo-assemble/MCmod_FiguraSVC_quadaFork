package net.knownsh.figurasvc.svc;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.knownsh.figurasvc.EventAccessor;
import net.knownsh.figurasvc.FiguraSVC;
import net.knownsh.figurasvc.LuaLockAccessor;
import net.knownsh.figurasvc.legacy.VoiceChatEventLegacy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.lua.FiguraLuaRuntime;
import org.figuramc.figura.lua.api.event.LuaEvent;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.Varargs;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static net.knownsh.figurasvc.svc.PCMUtils.pcmLuaDecode;
import static net.knownsh.figurasvc.svc.PCMUtils.pcmLuaEncode;

/**
 * ★★ 이 두 핸들러는 **음성 채팅 스레드**에서 불린다 (내부판 수정의 핵심 · 2026-09-30).
 *
 * 업스트림은 여기서 곧장 `avatar.run(...)` 을 불렀다. 그러면 ①같은 아바타의 Lua 를 렌더 스레드와 **동시에** 돌려
 * LuaJ 호출 기록이 깨지고(뒤이은 `require` · 키프레임 수식이 «vm error: ArrayIndexOutOfBoundsException» 으로 죽는다)
 * ②`Avatar.run` 이 큐에 쌓인 핑 · 아바타 초기화까지 **이 스레드에서** 비운다.
 * ⇒ 결과가 필요 없는 이벤트(MICROPHONE)는 메인 스레드로 넘기고,
 *   결과로 음성을 바꿔야 하는 HOST_MICROPHONE 만 여기서 — 아바타 잠금을 잡고, `Avatar.run` 을 거치지 않고 돌린다.
 * ⓘ 아바타는 `getLoadedAvatar` 로 찾는다 — `getAvatarForPlayer` 는 백엔드 fetch 부작용이 있어 매 음성 패킷마다 부르면 안 된다.
 */
@ForgeVoicechatPlugin
public class SVCPlugin implements VoicechatPlugin {
    /** HOST_MICROPHONE — 메인 스레드가 이 아바타 Lua 를 돌리는 중이면 이만큼만 기다리고, 넘으면 이번 음성 조각은 원음 그대로 보낸다 */
    private static final long HOST_LOCK_WAIT_MS = 5;

    @Override
    public String getPluginId() {
        return FiguraSVC.PLUGIN_ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        FiguraSVC.LOGGER.info("Registering events");
        registration.registerEvent(ClientSoundEvent.class, this::onLocalPlayerSpeak);
        registration.registerEvent(ClientReceiveSoundEvent.class, this::onLocalPlayerReceive);
    }

    /**
     * 다른 플레이어의 음성을 받았을 때 — 그 사람 아바타의 MICROPHONE 이벤트 (결과를 안 쓴다 → 메인 스레드로 넘긴다)
     * @param event the ClientReceiveSoundEvent
     */
    private void onLocalPlayerReceive(ClientReceiveSoundEvent event) {
        UUID speaker = event.getId();
        Avatar speakingPlayer = AvatarManager.getLoadedAvatar(speaker);
        // 핸들러 없는 아바타는 넘기지도 않는다 (이 스레드에서는 개수만 본다 — Lua 는 안 돈다)
        if (speakingPlayer == null || speakingPlayer.luaRuntime == null) return;
        if (((EventAccessor) speakingPlayer.luaRuntime.events).FiguraSVC$getMicrophoneEvent().__len() <= 0) return;

        short[] pcm = event.getRawAudio().clone(); // 음성 채팅이 배열을 다시 쓸 수 있어 복사해서 넘긴다
        Minecraft.getInstance().execute(() -> {
            // 넘어오는 사이에 아바타가 바뀌었을 수 있다 — 다시 찾는다
            Avatar avatar = AvatarManager.getLoadedAvatar(speaker);
            if (avatar == null || avatar.luaRuntime == null) return;
            LuaEvent microphoneEvent = ((EventAccessor) avatar.luaRuntime.events).FiguraSVC$getMicrophoneEvent();
            if (microphoneEvent.__len() <= 0) return;
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) return;
            Player player = level.getPlayerByUUID(speaker);
            if (player == null) return;

            avatar.run(
                    microphoneEvent,
                    avatar.render,
                    LuaString.valueOf(player.getName().getString()),
                    pcmLuaEncode(pcm));
        });
    }

    /**
     * 내가 말할 때 — 옛 svc.microphone (메인 스레드로 넘긴다) + 내 아바타의 HOST_MICROPHONE (여기서 · 잠금)
     */
    private void onLocalPlayerSpeak(ClientSoundEvent event) {
        VoiceChatEventLegacy.onLocalPlayerSpeakLegacy(event); // Legacy code support (for older avatars)

        Avatar localPlayer = AvatarManager.getLoadedAvatar(FiguraMod.getLocalPlayerUUID());
        if (localPlayer == null) return;
        FiguraLuaRuntime runtime = localPlayer.luaRuntime;
        if (runtime == null) return;
        LuaEvent microphoneEvent = ((EventAccessor) runtime.events).FiguraSVC$getHostMicrophoneEvent();
        if (microphoneEvent.__len() <= 0) return;

        // ★ 이 이벤트만은 바뀐 음성(PCM)을 **이번 조각에** 돌려줘야 해서 메인 스레드로 못 넘긴다.
        //   대신 아바타 잠금을 잡아 렌더 스레드와 겹치지 않게 하고, `Avatar.run` 대신 `runtime.run` 을 불러
        //   큐(핑 · 초기화)를 이 스레드에서 비우지 않는다.
        ReentrantLock lock = ((LuaLockAccessor) runtime).FiguraSVC$getLuaLock();
        boolean locked;
        try {
            locked = lock.tryLock(HOST_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) return; // 메인 스레드가 이 아바타 Lua 를 돌리는 중 — 이번 조각은 원음 그대로

        try {
            // 잠금을 기다리는 사이 리로드 · 스크립트 오류로 런타임이 바뀌었으면 손대지 않는다
            if (localPlayer.luaRuntime != runtime || !localPlayer.loaded) return;
            Varargs newPCM = runtime.run(microphoneEvent, localPlayer.render, pcmLuaEncode(event.getRawAudio()));

            // set new audio PCM
            try {
                if (newPCM != null) {
                    LuaTable newPCMTable = newPCM.checktable(1);
                    short[] newPCMData = pcmLuaDecode(newPCMTable);
                    event.setRawAudio(newPCMData);
                }
            } catch (Exception ignored) {}
        } finally {
            lock.unlock();
        }
    }
}
