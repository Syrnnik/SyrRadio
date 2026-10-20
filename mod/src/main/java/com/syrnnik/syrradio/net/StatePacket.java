package com.syrnnik.syrradio.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Весь список треков и где в нём радио.
 *
 * Треки до cursor уже играли, cursor — текущий, после — следующие. cursor
 * равный размеру списка значит «всё доиграло». positionMs — позиция текущего
 * в момент отправки; отрицательная значит «старт через столько-то»: сервер
 * даёт клиентам время докачать файл. Время сервера не передаём — часы у всех
 * разные, а задержка сети мала.
 *
 * serial растёт при каждом запуске трека, в том числе того же самого заново
 * (повтор трека, «назад» посреди трека): по нему клиент понимает, что звук
 * надо перезапустить. nextId — трек, который сервер уже рассылает заранее.
 */
public record StatePacket(
    List<TrackInfo> tracks,
    int cursor,
    boolean started,
    boolean paused,
    long positionMs,
    RepeatMode repeat,
    boolean shuffle,
    int serial,
    int nextId
) {
    public static final int MAX_TRACKS = 256;

    public void encode(FriendlyByteBuf buf) {
        int n = Math.min(tracks.size(), MAX_TRACKS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            tracks.get(i).encode(buf);
        }
        buf.writeVarInt(cursor);
        buf.writeBoolean(started);
        buf.writeBoolean(paused);
        buf.writeLong(positionMs);
        buf.writeEnum(repeat);
        buf.writeBoolean(shuffle);
        buf.writeVarInt(serial);
        buf.writeVarInt(nextId);
    }

    public static StatePacket decode(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_TRACKS);
        List<TrackInfo> tracks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tracks.add(TrackInfo.decode(buf));
        }
        return new StatePacket(tracks, buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readLong(),
            buf.readEnum(RepeatMode.class), buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
    }

    public TrackInfo current() {
        return cursor >= 0 && cursor < tracks.size() ? tracks.get(cursor) : null;
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            com.syrnnik.syrradio.client.ClientPackets.state(this));
    }
}
