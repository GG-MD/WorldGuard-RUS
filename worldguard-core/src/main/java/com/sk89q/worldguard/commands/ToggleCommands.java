/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.commands;

import com.sk89q.minecraft.util.commands.Command;
import com.sk89q.minecraft.util.commands.CommandContext;
import com.sk89q.minecraft.util.commands.CommandException;
import com.sk89q.minecraft.util.commands.CommandPermissions;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.util.formatting.component.CodeFormat;
import com.sk89q.worldedit.util.formatting.component.ErrorFormat;
import com.sk89q.worldedit.util.formatting.component.LabelFormat;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.event.ClickEvent;
import com.sk89q.worldedit.util.formatting.text.event.HoverEvent;
import com.sk89q.worldedit.util.formatting.text.format.TextColor;
import com.sk89q.worldedit.util.formatting.text.format.TextDecoration;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.config.ConfigurationManager;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.util.Entities;
import com.sk89q.worldguard.util.MessageBundle;

public class ToggleCommands {
    private final WorldGuard worldGuard;

    public ToggleCommands(WorldGuard worldGuard) {
        this.worldGuard = worldGuard;
    }

    @Command(aliases = {"stopfire"}, usage = "[<world>]",
            desc = "Disables all fire spread temporarily", max = 1)
    @CommandPermissions({"worldguard.fire-toggle.stop"})
    public void stopFire(CommandContext args, Actor sender) throws CommandException {
        
        World world;
        
        if (args.argsLength() == 0) {
            world = worldGuard.checkPlayer(sender).getWorld();
        } else {
            world = worldGuard.getPlatform().getMatcher().matchWorld(sender, args.getString(0));
        }
        
        WorldConfiguration wcfg = WorldGuard.getInstance().getPlatform().getGlobalStateManager().get(world);
        MessageBundle messages = worldGuard.getMessages();

        if (!wcfg.fireSpreadDisableToggle) {
            worldGuard.getPlatform().broadcastNotification(
                    LabelFormat.wrap(messages.format("commands.fire.disabled-broadcast",
                            "world", world.getName(), "actor", sender.getDisplayName())));
        } else {
            sender.print(messages.get("commands.fire.already-disabled"));
        }

        wcfg.fireSpreadDisableToggle = true;
    }

    @Command(aliases = {"allowfire"}, usage = "[<world>]",
            desc = "Allows all fire spread temporarily", max = 1)
    @CommandPermissions({"worldguard.fire-toggle.stop"})
    public void allowFire(CommandContext args, Actor sender) throws CommandException {
        
        World world;
        
        if (args.argsLength() == 0) {
            world = worldGuard.checkPlayer(sender).getWorld();
        } else {
            world = worldGuard.getPlatform().getMatcher().matchWorld(sender, args.getString(0));
        }
        
        WorldConfiguration wcfg = WorldGuard.getInstance().getPlatform().getGlobalStateManager().get(world);
        MessageBundle messages = worldGuard.getMessages();

        if (wcfg.fireSpreadDisableToggle) {
            worldGuard.getPlatform().broadcastNotification(LabelFormat.wrap(
                    messages.format("commands.fire.enabled-broadcast",
                            "world", world.getName(), "actor", sender.getDisplayName())));
        } else {
            sender.print(messages.get("commands.fire.already-enabled"));
        }

        wcfg.fireSpreadDisableToggle = false;
    }

    @Command(aliases = {"halt-activity", "stoplag", "haltactivity"}, usage = "[confirm]",
            desc = "Attempts to cease as much activity in order to stop lag", flags = "cis", max = 1)
    @CommandPermissions({"worldguard.halt-activity"})
    public void stopLag(CommandContext args, Actor sender) throws CommandException {

        ConfigurationManager configManager = WorldGuard.getInstance().getPlatform().getGlobalStateManager();
        MessageBundle messages = worldGuard.getMessages();

        if (args.hasFlag('i')) {
            if (configManager.activityHaltToggle) {
                 sender.print(messages.get("commands.halt-activity.intensive-not-allowed"));
            } else {
                 sender.print(messages.get("commands.halt-activity.intensive-allowed"));
            }
        } else {
            boolean activityHaltToggle = !args.hasFlag('c');

            if (activityHaltToggle && (args.argsLength() == 0 || !args.getString(0).equalsIgnoreCase("confirm"))) {
                String confirmCommand = "/" + args.getCommand() + " confirm";

                TextComponent message = TextComponent.builder("")
                        .append(ErrorFormat.wrap(messages.get("commands.halt-activity.confirm-prefix")))
                        .append(ErrorFormat.wrap(messages.get("commands.halt-activity.confirm-permanently"))
                                .decoration(TextDecoration.BOLD, TextDecoration.State.TRUE))
                        .append(ErrorFormat.wrap(messages.get("commands.halt-activity.confirm-suffix")))
                        .append(TextComponent.newline())
                        .append(TextComponent.of(messages.get("commands.halt-activity.confirm-click"), TextColor.GREEN)
                                .clickEvent(ClickEvent.of(ClickEvent.Action.RUN_COMMAND, confirmCommand))
                                .hoverEvent(HoverEvent.of(HoverEvent.Action.SHOW_TEXT, TextComponent.of(
                                        messages.format("commands.halt-activity.confirm-click-hover",
                                                "command", args.getCommand())))))
                        .append(ErrorFormat.wrap(messages.get("commands.halt-activity.confirm-or-type")))
                        .append(CodeFormat.wrap(confirmCommand)
                                .clickEvent(ClickEvent.of(ClickEvent.Action.SUGGEST_COMMAND, confirmCommand)))
                        .append(ErrorFormat.wrap(messages.get("commands.halt-activity.confirm-to-confirm")))
                        .build();

                sender.print(message);
                return;
            }

            configManager.activityHaltToggle = activityHaltToggle;

            if (activityHaltToggle) {
                if (!(sender instanceof LocalPlayer)) {
                    sender.print(messages.get("commands.halt-activity.halted"));
                }

                if (!args.hasFlag('s')) {
                    worldGuard.getPlatform().broadcastNotification(LabelFormat.wrap(
                            messages.format("commands.halt-activity.halted-broadcast",
                                    "actor", sender.getDisplayName())));
                } else {
                    sender.print(messages.format("commands.halt-activity.halted-silent",
                            "actor", sender.getDisplayName()));
                }

                for (World world : WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.GAME_HOOKS).getWorlds()) {
                    int removed = 0;

                    for (Entity entity : world.getEntities()) {
                        if (Entities.isIntensiveEntity(entity)) {
                            entity.remove();
                            removed++;
                        }
                    }

                    if (removed > 10) {
                        sender.printRaw(messages.format("commands.halt-activity.entities-removed",
                                "count", removed, "world", world.getName()));
                    }
                }
            } else {
                if (!args.hasFlag('s')) {
                    worldGuard.getPlatform().broadcastNotification(LabelFormat.wrap(
                            messages.get("commands.halt-activity.now-allowed")));

                    if (!(sender instanceof LocalPlayer)) {
                        sender.print(messages.get("commands.halt-activity.now-allowed"));
                    }
                } else {
                    sender.print(messages.get("commands.halt-activity.now-allowed-silent"));
                }
            }
        }
    }
}
