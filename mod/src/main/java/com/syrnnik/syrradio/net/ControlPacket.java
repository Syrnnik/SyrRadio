package com.syrnnik.syrradio.net;

import com.syrnnik.syrradio.server.RadioServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Кнопки плеера. trackId нужен для PLAY (какой включить) и для NEXT/PREV —
 * чтобы двойное нажатие не перелистнуло два трека: сервер выполнит команду,
 * только если играет именно тот трек, на который смотрел игрок.
 */
public record ControlPacket(Action action, int trackId) {
    public enum Action { PREV, NEXT, PAUSE, REPEAT, SHUFFLE, PLAY, CLEAR }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeVarInt(trackId);
    }

    public static ControlPacket decode(FriendlyByteBuf buf) {
        return new ControlPacket(buf.readEnum(Action.class), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        var player = ctx.get().getSender();
        if (player != null && RadioServer.get().isCompatible(player)) {
            RadioServer.get().control(action, trackId, player.getGameProfile().getName());
        }
    }
}
