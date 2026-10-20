package com.syrnnik.syrradio.net;

import com.syrnnik.syrradio.server.RadioServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Перетащить трек trackId так, чтобы он оказался на месте toIndex (с нуля). */
public record MovePacket(int trackId, int toIndex) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(trackId);
        buf.writeVarInt(toIndex);
    }

    public static MovePacket decode(FriendlyByteBuf buf) {
        return new MovePacket(buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        var player = ctx.get().getSender();
        if (player != null && RadioServer.get().isCompatible(player)) {
            RadioServer.get().move(trackId, toIndex);
        }
    }
}
