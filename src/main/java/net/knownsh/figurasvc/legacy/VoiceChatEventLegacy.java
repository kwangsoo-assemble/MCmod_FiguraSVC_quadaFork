package net.knownsh.figurasvc.legacy;

import com.mojang.datafixers.util.Pair;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import net.knownsh.figurasvc.EventAccessor;
import net.minecraft.client.Minecraft;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.entries.FiguraEvent;
import org.figuramc.figura.entries.annotations.FiguraEventPlugin;
import org.figuramc.figura.lua.LuaWhitelist;
import org.figuramc.figura.lua.api.event.LuaEvent;
import org.figuramc.figura.lua.docs.LuaFieldDoc;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaTable;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

/**
 * Legacy FiguraSVC event, maintained to keep old figuraSVC avatars functional on newer servers/clients
 */
@FiguraEventPlugin
public class VoiceChatEventLegacy implements FiguraEvent {
    @LuaWhitelist
    @LuaFieldDoc("svc.microphone")
    public static LuaEvent MICROPHONE = new LuaEvent();

    @Override
    public String getID() {
        return "svc";
    }

    @Override
    public Collection<Pair<String, LuaEvent>> getEvents() {
        return Collections.singleton(new Pair<>("MICROPHONE", MICROPHONE));
    }

    /**
     * 내가 말할 때 — 옛 `svc.microphone` 이벤트 (음성 캡처 스레드에서 불린다)
     *
     * ★★ 업스트림은 여기서 **핸들러가 있든 없든** 음성 조각마다(약 20ms) 내 아바타의 `avatar.run(...)` 을 불렀다.
     *   `Avatar.run` 은 먼저 큐의 핑 · 아바타 초기화를 비우므로, 말하는 동안 그 일들이 이 스레드에서 렌더 스레드와
     *   **동시에** 돌아 LuaJ 호출 기록이 깨졌다 (마도사졸업시험 탤런트 `darkhold/ascension:1` · 2026-09-30).
     *   `luaRuntime` null 검사도 없어 불러오는 중에는 이 스레드에서 NPE 가 났다.
     * ⇒ 핸들러가 있을 때만, 메인 스레드로 넘겨 돌린다 (결과는 안 쓴다). PCM 표는 옛 판 그대로 0 부터 채운다.
     */
    public static void onLocalPlayerSpeakLegacy(ClientSoundEvent event) {
        if (event.getVoicechat().isMuted()) return;
        UUID owner = FiguraMod.getLocalPlayerUUID();
        Avatar avatar = AvatarManager.getLoadedAvatar(owner);
        if (avatar == null || avatar.luaRuntime == null) return;
        if (((EventAccessor) avatar.luaRuntime.events).FiguraSVC$getMicrophoneEventLegacy().__len() <= 0) return;

        short[] pcm = event.getRawAudio().clone(); // 음성 채팅이 배열을 다시 쓸 수 있어 복사해서 넘긴다
        Minecraft.getInstance().execute(() -> {
            // 넘어오는 사이에 아바타가 바뀌었을 수 있다 — 다시 찾는다
            Avatar current = AvatarManager.getLoadedAvatar(owner);
            if (current == null || current.luaRuntime == null) return;
            LuaEvent legacyMicrophoneEvent = ((EventAccessor) current.luaRuntime.events).FiguraSVC$getMicrophoneEventLegacy();
            if (legacyMicrophoneEvent.__len() <= 0) return;

            LuaTable pCMTable = new LuaTable();
            for (int i = 0; i < pcm.length; i++) {
                pCMTable.set(i, LuaNumber.valueOf(pcm[i]));
            }
            current.run(legacyMicrophoneEvent, current.tick, pCMTable);
        });
    }
}
