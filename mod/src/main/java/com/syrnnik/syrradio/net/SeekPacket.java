package com.syrnnik.syrradio.net;

import com.syrnnik.syrradio.server.RadioServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Перемотать трек trackId на positionMs. Если играет уже другой трек — ничего не делаем. */
public record SeekPacket(int trackId, long positionMs) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(trackId);
        buf.writeVarLong(positionMs);
    }

    public static SeekPacket decode(FriendlyByteBuf buf) {
        return new SeekPacket(buf.readVarInt(), buf.readVarLong());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        var player = ctx.get().getSender();
        if (player != null && RadioServer.get().isCompatible(player)) {
            RadioServer.get().seek(trackId, positionMs, player.getGameProfile().getName());
        }
    }
}
