package com.syrnnik.syrradio.net;

import net.minecraft.network.FriendlyByteBuf;

/** Трек в списке так, как его видит клиент. */
public record TrackInfo(
    int id,
    String title,
    String artist,
    String source,
    long durationMs,
    String requestedBy,
    Status status
) {
    /** Готовность файла на сервере. */
    public enum Status { QUEUED, PREPARING, READY, FAILED }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeUtf(title, 512);
        buf.writeUtf(artist, 512);
        buf.writeUtf(source, 64);
        buf.writeVarLong(durationMs);
        buf.writeUtf(requestedBy, 64);
        buf.writeEnum(status);
    }

    public static TrackInfo decode(FriendlyByteBuf buf) {
        return new TrackInfo(
            buf.readVarInt(),
            buf.readUtf(512),
            buf.readUtf(512),
            buf.readUtf(64),
            buf.readVarLong(),
            buf.readUtf(64),
            buf.readEnum(Status.class));
    }

    public String displayName() {
        return artist.isBlank() || artist.equals("—") ? title : artist + " — " + title;
    }
}
