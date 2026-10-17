package com.syrnnik.syrradio.client;

import com.syrnnik.syrradio.net.ChunkPacket;
import com.syrnnik.syrradio.net.HelloPacket;
import com.syrnnik.syrradio.net.NoticePacket;
import com.syrnnik.syrradio.net.StatePacket;

/** Входящие пакеты на клиенте. Отдельный класс, чтобы выделенный сервер его не грузил. */
public final class ClientPackets {
    private ClientPackets() {}

    public static void hello(HelloPacket p) {
        ClientRadio.get().onHello(p.protocol());
    }

    public static void state(StatePacket p) {
        ClientRadio.get().onState(p);
    }

    public static void chunk(ChunkPacket p) {
        ClientRadio.get().onChunk(p);
    }

    public static void notice(NoticePacket p) {
        ClientRadio.get().onNotice(p);
    }
}
