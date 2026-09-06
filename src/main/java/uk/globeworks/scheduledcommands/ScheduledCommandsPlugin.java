package uk.globeworks.scheduledcommands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class ScheduledCommandsPlugin extends JavaPlugin {

    private ScheduleManager scheduleManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        scheduleManager = new ScheduleManager(this);
        scheduleManager.loadAndStart();
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
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
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
        sender.sendMessage("§eUsage: /schedcmds reload");
        return true;
    }
}
