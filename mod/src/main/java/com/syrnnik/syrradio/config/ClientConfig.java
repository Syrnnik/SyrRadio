package com.syrnnik.syrradio.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue VOLUME;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        VOLUME = b
            .comment("Громкость радио, от 0 до 1. Умножается на общую громкость игры.",
                "0 — радио у себя выключено: сервер не присылает треки, обычная музыка игры не мешает.")
            .defineInRange("volume", 0.5, 0.0, 1.0);
        SPEC = b.build();
    }

    private ClientConfig() {}
}
