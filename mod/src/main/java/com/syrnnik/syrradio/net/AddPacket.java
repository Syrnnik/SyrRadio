package com.syrnnik.syrradio.net;

import com.syrnnik.syrradio.server.RadioServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record AddPacket(String url) {
    public static final int MAX_URL = 2048;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(url, MAX_URL);
    }

    public static AddPacket decode(FriendlyByteBuf buf) {
        return new AddPacket(buf.readUtf(MAX_URL));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        var player = ctx.get().getSender();
        if (player != null && RadioServer.get().isCompatible(player)) {
            RadioServer.get().add(url, player.createCommandSourceStack());
        }
    }
}
