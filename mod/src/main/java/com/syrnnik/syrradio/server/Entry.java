package com.syrnnik.syrradio.server;

import com.syrnnik.syrradio.net.Net;
import com.syrnnik.syrradio.net.TrackInfo;

/** Трек в списке на сервере. */
final class Entry {
    enum State { QUEUED, PREPARING, READY, FAILED }

    final int id;
    final String url;
    final String title;
    final String artist;
    final String source;
    final String requestedBy;
    long durationMs;
    State state = State.QUEUED;
    /** Готовый ogg. Держим только у текущего и следующего трека. */
    byte[] data;

    Entry(int id, String url, String title, String artist, String source, String requestedBy, long durationMs) {
        this.id = id;
        this.url = url;
        this.title = title.isBlank() ? url : title;
        this.artist = artist;
        this.source = source;
        this.requestedBy = requestedBy;
        this.durationMs = durationMs;
    }

    Entry(int id, String url, BridgeClient.Meta meta, String requestedBy) {
        this(id, url, meta.title(), meta.artist(), meta.source(), requestedBy, meta.durationMs());
    }

    int chunkCount() {
        return data == null ? 0 : (data.length + Net.CHUNK_SIZE - 1) / Net.CHUNK_SIZE;
    }

    TrackInfo info() {
        TrackInfo.Status s = switch (state) {
            case READY -> TrackInfo.Status.READY;
            case PREPARING -> TrackInfo.Status.PREPARING;
            case FAILED -> TrackInfo.Status.FAILED;
            default -> TrackInfo.Status.QUEUED;
        };
        return new TrackInfo(id, title, artist, source, durationMs, requestedBy, s);
    }

    String displayName() {
        return artist.isBlank() || artist.equals("—") ? title : artist + " — " + title;
    }
}
