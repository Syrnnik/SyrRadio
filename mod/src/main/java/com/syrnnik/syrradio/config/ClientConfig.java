package com.syrnnik.syrradio.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.DoubleValue VOLUME;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        ENABLED = b
            .comment("Слушать ли радио. Выключенному сервер треки не присылает.")
            .define("enabled", true);
        VOLUME = b
            .comment("Громкость радио, от 0 до 1. Умножается на общую громкость игры.")
            .defineInRange("volume", 0.5, 0.0, 1.0);
        SPEC = b.build();
    }

    private ClientConfig() {}
}
