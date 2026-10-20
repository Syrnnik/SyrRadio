package com.syrnnik.syrradio.net;

import com.syrnnik.syrradio.SyrRadio;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Сетевой канал мода.
 *
 * Канал принимает любую версию и отсутствие мода с любой стороны: игрока без
 * мода пускает на сервер, он просто не слышит радио. Совместимость версий
 * проверяем сами пакетом Hello — его формат не меняется никогда, а остальные
 * пакеты ходят только после того, как версии совпали.
 */
public final class Net {
    /** Меняется при любом изменении формата пакетов, кроме Hello. */
    public static final int PROTOCOL = 2;
    /** Размер куска трека в пакете. */
    public static final int CHUNK_SIZE = 32 * 1024;

    public static SimpleChannel CHANNEL;

    private Net() {}

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(SyrRadio.MOD_ID, "main"),
            () -> "any",
            v -> true,
            v -> true);

        int id = 0;
        // Hello всегда под номером 0 и с одним int внутри.
        CHANNEL.messageBuilder(HelloPacket.class, id++)
            .encoder(HelloPacket::encode).decoder(HelloPacket::decode)
            .consumerMainThread(HelloPacket::handle).add();
        CHANNEL.messageBuilder(StatePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(StatePacket::encode).decoder(StatePacket::decode)
            .consumerMainThread(StatePacket::handle).add();
        CHANNEL.messageBuilder(ChunkPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ChunkPacket::encode).decoder(ChunkPacket::decode)
            .consumerMainThread(ChunkPacket::handle).add();
        CHANNEL.messageBuilder(NoticePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(NoticePacket::encode).decoder(NoticePacket::decode)
            .consumerMainThread(NoticePacket::handle).add();
        CHANNEL.messageBuilder(AddPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(AddPacket::encode).decoder(AddPacket::decode)
            .consumerMainThread(AddPacket::handle).add();
        CHANNEL.messageBuilder(ControlPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ControlPacket::encode).decoder(ControlPacket::decode)
            .consumerMainThread(ControlPacket::handle).add();
        CHANNEL.messageBuilder(RemovePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(RemovePacket::encode).decoder(RemovePacket::decode)
            .consumerMainThread(RemovePacket::handle).add();
        CHANNEL.messageBuilder(ListeningPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ListeningPacket::encode).decoder(ListeningPacket::decode)
            .consumerMainThread(ListeningPacket::handle).add();
        CHANNEL.messageBuilder(MovePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(MovePacket::encode).decoder(MovePacket::decode)
            .consumerMainThread(MovePacket::handle).add();
        CHANNEL.messageBuilder(SeekPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(SeekPacket::encode).decoder(SeekPacket::decode)
            .consumerMainThread(SeekPacket::handle).add();
    }

    public static boolean hasMod(ServerPlayer player) {
        Connection c = player.connection.connection;
        return CHANNEL.isRemotePresent(c);
    }

    public static void send(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    public static void toServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }
}
