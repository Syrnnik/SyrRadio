package com.syrnnik.syrradio;

import com.syrnnik.syrradio.client.ClientEvents;
import com.syrnnik.syrradio.config.ClientConfig;
import com.syrnnik.syrradio.config.ServerConfig;
import com.syrnnik.syrradio.net.Net;
import com.syrnnik.syrradio.server.ServerEvents;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Одна музыка на весь сервер.
 *
 * Треки качает отдельный сервис (bridge) — мод только держит очередь,
 * рассылает готовый ogg игрокам и проигрывает его у них синхронно.
 */
@Mod(SyrRadio.MOD_ID)
public class SyrRadio {
    public static final String MOD_ID = "syrradio";
    public static final Logger LOG = LogUtils.getLogger();

    public SyrRadio() {
        ModLoadingContext ctx = ModLoadingContext.get();
        // COMMON, а не SERVER: файл лежит в общей папке config/, а не внутри мира,
        // так его проще положить в контейнер.
        ctx.registerConfig(ModConfig.Type.COMMON, ServerConfig.SPEC, "syrradio-server.toml");
        ctx.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "syrradio-client.toml");

        Net.register();
        MinecraftForge.EVENT_BUS.register(ServerEvents.class);

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientEvents.init(modBus));
    }
}
