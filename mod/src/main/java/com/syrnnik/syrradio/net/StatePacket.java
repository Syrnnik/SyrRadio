package com.syrnnik.syrradio.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Что играет и что в очереди.
 *
 * positionMs — позиция текущего трека в момент отправки. Отрицательная значит
 * «старт через столько-то»: сервер даёт клиентам время докачать файл. Время
 * сервера не передаём — часы у всех разные, а задержка сети мала.
 */
public record StatePacket(TrackInfo current, long positionMs, List<TrackInfo> queue) {
    public static final int MAX_QUEUE = 50;

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(current != null);
        if (current != null) {
            current.encode(buf);
            buf.writeLong(positionMs);
        }
        int n = Math.min(queue.size(), MAX_QUEUE);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            queue.get(i).encode(buf);
        }
    }

    public static StatePacket decode(FriendlyByteBuf buf) {
        TrackInfo current = null;
        long pos = 0;
        if (buf.readBoolean()) {
            current = TrackInfo.decode(buf);
            pos = buf.readLong();
        }
        int n = Math.min(buf.readVarInt(), MAX_QUEUE);
        List<TrackInfo> queue = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            queue.add(TrackInfo.decode(buf));
        }
        return new StatePacket(current, pos, queue);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            com.syrnnik.syrradio.client.ClientPackets.state(this));
    }
}
