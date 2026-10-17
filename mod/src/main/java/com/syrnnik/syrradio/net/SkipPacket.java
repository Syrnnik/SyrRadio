package com.syrnnik.syrradio.net;

import com.syrnnik.syrradio.server.RadioServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Пропустить трек trackId. Номер нужен, чтобы двойной клик не пропустил два трека. */
public record SkipPacket(int trackId) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(trackId);
    }

    public static SkipPacket decode(FriendlyByteBuf buf) {
        return new SkipPacket(buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        var player = ctx.get().getSender();
        if (player != null && RadioServer.get().isCompatible(player)) {
            RadioServer.get().skip(trackId, player.getGameProfile().getName());
        }
    }
}
