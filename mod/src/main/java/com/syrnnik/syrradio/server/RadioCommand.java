package com.syrnnik.syrradio.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /syrradio — то же, что экран радио, но из чата, консоли и RCON.
 * Пользоваться может любой игрок: пропускать треки на сервере разрешено всем.
 */
final class RadioCommand {
    private RadioCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("syrradio")
            .then(Commands.literal("add")
                .then(Commands.argument("url", StringArgumentType.greedyString())
                    .executes(c -> {
                        RadioServer.get().add(StringArgumentType.getString(c, "url"), c.getSource());
                        return 1;
                    })))
            .then(Commands.literal("skip")
                .executes(c -> {
                    boolean ok = RadioServer.get().skipCurrent(c.getSource().getTextName());
                    c.getSource().sendSuccess(() -> ok
                        ? Component.translatableWithFallback("syrradio.command.skipped", "Skipped")
                        : Component.translatableWithFallback("syrradio.command.nothing", "Nothing is playing"), false);
                    return ok ? 1 : 0;
                }))
            .then(Commands.literal("stop")
                .executes(c -> {
                    RadioServer.get().stopAll();
                    c.getSource().sendSuccess(() ->
                        Component.translatableWithFallback("syrradio.command.stopped", "Radio stopped, queue cleared"), true);
                    return 1;
                }))
            .then(Commands.literal("queue")
                .executes(c -> {
                    var lines = RadioServer.get().describe();
                    if (lines.isEmpty()) {
                        c.getSource().sendSuccess(() ->
                            Component.translatableWithFallback("syrradio.command.nothing", "Nothing is playing"), false);
                    }
                    for (String line : lines) {
                        c.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return lines.size();
                })));
    }
}
