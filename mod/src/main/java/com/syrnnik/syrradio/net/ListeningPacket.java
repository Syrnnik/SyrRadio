package com.syrnnik.syrradio.net;

import com.syrnnik.syrradio.server.RadioServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Игрок включил или выключил радио у себя. Выключенному файлы не шлём. */
public record ListeningPacket(boolean listening) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(listening);
    }

    public static ListeningPacket decode(FriendlyByteBuf buf) {
        return new ListeningPacket(buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        var player = ctx.get().getSender();
        if (player != null && RadioServer.get().isCompatible(player)) {
            RadioServer.get().setListening(player, listening);
        }
    }
}
