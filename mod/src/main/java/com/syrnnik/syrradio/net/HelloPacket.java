package com.syrnnik.syrradio.net;

import com.syrnnik.syrradio.server.RadioServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Рукопожатие. Сервер шлёт свою версию протокола при входе игрока, клиент
 * отвечает своей. Формат пакета заморожен: один int.
 */
public record HelloPacket(int protocol) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(protocol);
    }

    public static HelloPacket decode(FriendlyByteBuf buf) {
        return new HelloPacket(buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        if (c.getDirection().getReceptionSide().isServer()) {
            if (c.getSender() != null) {
                RadioServer.get().onHello(c.getSender(), protocol);
            }
        } else {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                com.syrnnik.syrradio.client.ClientPackets.hello(this));
        }
    }
}
