package com.syrnnik.syrradio.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ServerConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> BRIDGE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> BRIDGE_TOKEN;
    public static final ForgeConfigSpec.IntValue CHUNKS_PER_TICK;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        BRIDGE_URL = b
            .comment("Адрес сервиса, который качает треки (bridge).")
            .define("bridgeUrl", "http://syrradio-bridge:8080");
        BRIDGE_TOKEN = b
            .comment("Секрет bridge, BRIDGE_TOKEN из его .env.")
            .define("bridgeToken", "");
        CHUNKS_PER_TICK = b
            .comment("Сколько кусков по 32 КБ отправлять каждому игроку за тик.",
                "4 — около 2,5 МБ/с на игрока: трек долетает за пару секунд и не мешает игре.")
            .defineInRange("chunksPerTick", 4, 1, 32);
        SPEC = b.build();
    }

    private ServerConfig() {}
}
