package uk.globeworks.scheduledcommands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class ScheduledCommandsPlugin extends JavaPlugin implements TabCompleter {

    private ScheduleManager scheduleManager;

    @Override
    public void onEnable() {
        getLogger().info(Globeworks.logo("ScheduledCommands", getDescription().getVersion()));
        saveDefaultConfig();
        scheduleManager = new ScheduleManager(this);
        scheduleManager.loadAndStart();
        var cmd = getCommand("schedcmds");
        if (cmd != null) {
            cmd.setTabCompleter(this);
        }
        getLogger().info("ScheduledCommands enabled.");
    }

    @Override
    public void onDisable() {
        if (scheduleManager != null) {
            scheduleManager.shutdown();
        }
        getLogger().info("ScheduledCommands disabled.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eUsage: /schedcmds <reload|list|test|debug> [args]");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("schedcmds.reload")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            reloadConfig();
            scheduleManager.shutdown();
            scheduleManager.loadAndStart();
            sender.sendMessage("§aSchedules reloaded.");
            return true;
        }

        if (sub.equals("list") || sub.equals("ls")) {
            if (!sender.hasPermission("schedcmds.list")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            Map<String, ScheduleManager.ScheduleInfo> schedules = scheduleManager.getSchedules();
            if (schedules.isEmpty()) {
                sender.sendMessage("§eNo schedules loaded.");
                return true;
            }
            sender.sendMessage("§aSchedules (" + schedules.size() + "):");
            for (ScheduleManager.ScheduleInfo info : schedules.values()) {
                sender.sendMessage("§f- §b" + info.name()
                    + " §7| §f" + info.whenDescription()
                    + " §7| §e" + info.commandCount() + " command(s)");
            }
            return true;
        }

        if (sub.equals("test") || sub.equals("run") || sub.equals("execute")) {
            if (!sender.hasPermission("schedcmds.test")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§eUsage: /schedcmds test <schedule-name>");
                return true;
            }
            String name = args[1];
            boolean ok = scheduleManager.runScheduleNow(name);
            if (ok) {
                sender.sendMessage("§aExecuted schedule §b" + name + "§a.");
            } else {
                sender.sendMessage("§cUnknown schedule: §f" + name
                    + "§c. Use /schedcmds list to see available schedules.");
            }
            return true;
        }

        if (sub.equals("debug")) {
            if (!sender.hasPermission("schedcmds.debug")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            if (args.length == 1) {
                // toggle
                boolean now = scheduleManager.setDebug(!scheduleManager.isDebug());
                sender.sendMessage(now
                    ? "§aDebug logging §fenabled§a."
                    : "§eDebug logging §fdisabled§e.");
                return true;
            }
            String arg = args[1].toLowerCase(Locale.ROOT);
            if (arg.equals("on") || arg.equals("true") || arg.equals("enable") || arg.equals("1")) {
                scheduleManager.setDebug(true);
                sender.sendMessage("§aDebug logging §fenabled§a.");
            } else if (arg.equals("off") || arg.equals("false") || arg.equals("disable") || arg.equals("0")) {
                scheduleManager.setDebug(false);
                sender.sendMessage("§eDebug logging §fdisabled§e.");
            } else if (arg.equals("toggle")) {
                boolean now = scheduleManager.setDebug(!scheduleManager.isDebug());
                sender.sendMessage(now
                    ? "§aDebug logging §fenabled§a."
                    : "§eDebug logging §fdisabled§e.");
            } else if (arg.equals("status") || arg.equals("?")) {
                sender.sendMessage("§7Debug logging is currently "
                    + (scheduleManager.isDebug() ? "§aon" : "§coff") + "§7.");
            } else {
                sender.sendMessage("§eUsage: /schedcmds debug [on|off|toggle|status]");
            }
            return true;
        }

        sender.sendMessage("§eUsage: /schedcmds <reload|list|test|debug> [args]");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("schedcmds.reload")) subs.add("reload");
            if (sender.hasPermission("schedcmds.list")) subs.add("list");
            if (sender.hasPermission("schedcmds.test")) subs.add("test");
            if (sender.hasPermission("schedcmds.debug")) subs.add("debug");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return subs.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("test")
            || args[0].equalsIgnoreCase("run")
            || args[0].equalsIgnoreCase("execute"))) {
            if (!sender.hasPermission("schedcmds.test")) {
                return List.of();
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return scheduleManager.getSchedules().keySet().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            if (!sender.hasPermission("schedcmds.debug")) {
                return List.of();
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("on", "off", "toggle", "status").stream()
                .filter(s -> s.startsWith(prefix))
                .collect(Collectors.toList());
        }
        return List.of();
    }
}
