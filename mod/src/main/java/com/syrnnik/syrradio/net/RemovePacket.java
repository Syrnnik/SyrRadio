package com.syrnnik.syrradio.net;

import com.syrnnik.syrradio.server.RadioServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Убрать трек из очереди (не текущий — для него есть Skip). */
public record RemovePacket(int trackId) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(trackId);
    }

    public static RemovePacket decode(FriendlyByteBuf buf) {
        return new RemovePacket(buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        var player = ctx.get().getSender();
        if (player != null && RadioServer.get().isCompatible(player)) {
            RadioServer.get().remove(trackId);
        }
    }
}
