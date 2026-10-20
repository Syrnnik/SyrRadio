package com.syrnnik.syrradio.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.syrnnik.syrradio.net.RepeatMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /syrradio — то же, что экран радио, но из чата, консоли и RCON.
 * Пользоваться может любой игрок: управлять радио на сервере разрешено всем.
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
            .then(Commands.literal("next").executes(c -> reply(c, RadioServer.get().next(who(c)), "syrradio.command.next", "Next track")))
            .then(Commands.literal("skip").executes(c -> reply(c, RadioServer.get().next(who(c)), "syrradio.command.next", "Next track")))
            .then(Commands.literal("prev").executes(c -> reply(c, RadioServer.get().prev(who(c)), "syrradio.command.prev", "Previous track")))
            .then(Commands.literal("pause").executes(c -> reply(c, RadioServer.get().togglePause(who(c)), "syrradio.command.pause", "Pause toggled")))
            .then(Commands.literal("repeat")
                .then(Commands.literal("off").executes(c -> repeat(c, RepeatMode.OFF)))
                .then(Commands.literal("all").executes(c -> repeat(c, RepeatMode.ALL)))
                .then(Commands.literal("one").executes(c -> repeat(c, RepeatMode.ONE))))
            .then(Commands.literal("shuffle")
                .then(Commands.literal("on").executes(c -> shuffle(c, true)))
                .then(Commands.literal("off").executes(c -> shuffle(c, false))))
            .then(Commands.literal("play")
                .then(Commands.argument("position", IntegerArgumentType.integer(1))
                    .executes(c -> reply(c, RadioServer.get().playAt(IntegerArgumentType.getInteger(c, "position"), who(c)),
                        "syrradio.command.play", "Playing"))))
            .then(Commands.literal("remove")
                .then(Commands.argument("position", IntegerArgumentType.integer(1))
                    .executes(c -> reply(c, RadioServer.get().removeAt(IntegerArgumentType.getInteger(c, "position")),
                        "syrradio.command.removed", "Removed from the list"))))
            .then(Commands.literal("clear")
                .executes(c -> {
                    RadioServer.get().clear(who(c));
                    c.getSource().sendSuccess(() ->
                        Component.translatableWithFallback("syrradio.command.cleared", "List cleared"), true);
                    return 1;
                }))
            .then(Commands.literal("queue")
                .executes(c -> {
                    var lines = RadioServer.get().describe();
                    for (String line : lines) {
                        c.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return lines.size();
                })));
    }

    private static String who(CommandContext<CommandSourceStack> c) {
        return c.getSource().getTextName();
    }

    private static int reply(CommandContext<CommandSourceStack> c, boolean ok, String key, String fallback) {
        c.getSource().sendSuccess(() -> ok
            ? Component.translatableWithFallback(key, fallback)
            : Component.translatableWithFallback("syrradio.command.nothing", "Nothing to do"), false);
        return ok ? 1 : 0;
    }

    private static int repeat(CommandContext<CommandSourceStack> c, RepeatMode mode) {
        RadioServer.get().setRepeat(mode);
        c.getSource().sendSuccess(() -> Component.literal("repeat " + mode.name().toLowerCase()), false);
        return 1;
    }

    private static int shuffle(CommandContext<CommandSourceStack> c, boolean on) {
        RadioServer.get().setShuffle(on);
        c.getSource().sendSuccess(() -> Component.literal("shuffle " + (on ? "on" : "off")), false);
        return 1;
    }
}
