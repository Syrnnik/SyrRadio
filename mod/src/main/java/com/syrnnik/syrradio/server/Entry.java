package com.syrnnik.syrradio.server;

import com.syrnnik.syrradio.net.Net;
import com.syrnnik.syrradio.net.TrackInfo;

/** Трек в очереди на сервере. */
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

    Entry(int id, String url, BridgeClient.Meta meta, String requestedBy) {
        this.id = id;
        this.url = url;
        this.title = meta.title().isBlank() ? url : meta.title();
        this.artist = meta.artist();
        this.source = meta.source();
        this.durationMs = meta.durationMs();
        this.requestedBy = requestedBy;
    }

    int chunkCount() {
        return data == null ? 0 : (data.length + Net.CHUNK_SIZE - 1) / Net.CHUNK_SIZE;
    }

    TrackInfo info(boolean started) {
        TrackInfo.Status s = switch (state) {
            case READY -> started ? TrackInfo.Status.READY : TrackInfo.Status.PREPARING;
            case PREPARING -> TrackInfo.Status.PREPARING;
            default -> TrackInfo.Status.QUEUED;
        };
        return new TrackInfo(id, title, artist, source, durationMs, requestedBy, s);
    }

    String displayName() {
        return artist.isBlank() || artist.equals("—") ? title : artist + " — " + title;
    }
}
