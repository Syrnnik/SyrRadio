package com.syrnnik.syrradio.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ServerConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> BRIDGE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> BRIDGE_TOKEN;
    public static final ForgeConfigSpec.IntValue CHUNKS_PER_TICK;
    public static final ForgeConfigSpec.ConfigValue<String> MISSING_MOD_RU;
    public static final ForgeConfigSpec.ConfigValue<String> OUTDATED_MOD_RU;
    public static final ForgeConfigSpec.ConfigValue<String> MISSING_MOD_EN;
    public static final ForgeConfigSpec.ConfigValue<String> OUTDATED_MOD_EN;
    public static final ForgeConfigSpec.ConfigValue<String> DOWNLOAD_URL;

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
        b.comment("Сообщение в чат игроку, который зашёл без мода или со старой версией.",
                "Язык выбирается по языку игры у игрока: русский — ru, любой другой — en.",
                "В конце сообщения дописывается ссылка downloadUrl. Пустая строка — не писать.")
            .push("joinMessages");
        DOWNLOAD_URL = b
            .define("downloadUrl", "https://github.com/Syrnnik/SyrRadio/releases/latest");
        b.push("ru");
        MISSING_MOD_RU = b
            .define("missingMod", "На сервере играет радио SyrRadio, но у тебя не установлен мод. "
                + "Поставь его, чтобы слушать музыку вместе со всеми:");
        OUTDATED_MOD_RU = b
            .define("outdatedMod", "У тебя старая версия SyrRadio, поэтому радио не играет. Обнови мод:");
        b.pop();
        b.push("en");
        MISSING_MOD_EN = b
            .define("missingMod", "This server plays music with the SyrRadio mod, but you don't have it. "
                + "Install it to listen together with everyone:");
        OUTDATED_MOD_EN = b
            .define("outdatedMod", "Your SyrRadio is outdated, so the radio is silent for you. Update the mod:");
        b.pop();
        b.pop();
        SPEC = b.build();
    }

    private ServerConfig() {}
}
