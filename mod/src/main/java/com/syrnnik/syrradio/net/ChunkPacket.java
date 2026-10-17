package com.syrnnik.syrradio.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Кусок ogg-файла трека trackId. */
public record ChunkPacket(int trackId, int index, int totalSize, byte[] data) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(trackId);
        buf.writeVarInt(index);
        buf.writeVarInt(totalSize);
        buf.writeByteArray(data);
    }

    public static ChunkPacket decode(FriendlyByteBuf buf) {
        return new ChunkPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(Net.CHUNK_SIZE));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            com.syrnnik.syrradio.client.ClientPackets.chunk(this));
    }
}
