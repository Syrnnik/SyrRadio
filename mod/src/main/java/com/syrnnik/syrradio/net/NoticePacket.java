package com.syrnnik.syrradio.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Короткое сообщение игроку: «добавлено», «не удалось» и т.п. */
public record NoticePacket(Component text, boolean error) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeComponent(text);
        buf.writeBoolean(error);
    }

    public static NoticePacket decode(FriendlyByteBuf buf) {
        return new NoticePacket(buf.readComponent(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            com.syrnnik.syrradio.client.ClientPackets.notice(this));
    }
}
