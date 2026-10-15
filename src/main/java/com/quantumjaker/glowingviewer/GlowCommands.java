// Copyright (c) 2026 QuantumJAKER.
// SPDX-License-Identifier: CC-BY-NC-SA-4.0
package com.quantumjaker.glowingviewer;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Collection;

/**
 * Registers {@code /glow give} and {@code /glow clear}.
 *
 * <p>Only vanilla argument types are used, so the commands work from
 * {@code execute}, command blocks and datapacks, and vanilla clients receive
 * correct command-tree suggestions (serverside-only compatible).</p>
 *
 * <pre>
 * /glow give &lt;targets&gt; &lt;durationSeconds&gt; &lt;viewers&gt;
 * /glow clear &lt;targets&gt; [viewers]
 * </pre>
 */
public final class GlowCommands {
    private GlowCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("glow")
                .requires(source -> source.hasPermission(2))
                .executes(GlowCommands::usage)
                .then(Commands.literal("give")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("duration", IntegerArgumentType.integer(1, 1000000))
                                        .then(Commands.argument("viewers", EntityArgument.players())
                                                .executes(context -> give(context,
                                                        IntegerArgumentType.getInteger(context, "duration") * 20L))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> clear(context, null))
                                .then(Commands.argument("viewers", EntityArgument.players())
                                        .executes(context -> {
                                            Collection<ServerPlayer> viewers =
                                                    EntityArgument.getPlayers(context, "viewers");
                                            return clear(context, viewers);
                                        })))));
    }

    private static int usage(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "Usage:\n"
                        + "/glow give <targets> <durationSeconds> <viewers>\n"
                        + "/glow clear <targets> [viewers]"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int give(CommandContext<CommandSourceStack> context, long durationTicks)
            throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
        Collection<ServerPlayer> viewers = EntityArgument.getPlayers(context, "viewers");
        GlowManager.setGlowing(targets, viewers, durationTicks);
        context.getSource().sendSuccess(
                () -> Component.literal(String.format("%d entities now glow for %d player(s) (%ds).",
                        targets.size(), viewers.size(), durationTicks / 20)),
                true);
        return targets.size();
    }

    private static int clear(CommandContext<CommandSourceStack> context,
            Collection<ServerPlayer> viewers) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
        GlowManager.clearGlowing(targets, viewers);
        context.getSource().sendSuccess(
                () -> Component.literal(String.format("Removed glowing overrides for %d entities.",
                        targets.size())),
                true);
        return targets.size();
    }
}
