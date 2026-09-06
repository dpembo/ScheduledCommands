package com.example.scheduledcommands;

import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.cronutils.model.CronType.UNIX;
import static com.cronutils.model.CronType.QUARTZ;

public class ScheduleManager implements Listener {

    private final ScheduledCommandsPlugin plugin;
    private final Map<String, BukkitTask> runningTasks = new HashMap<>();
    private final List<JoinSchedule> joinSchedules = new ArrayList<>();
    private final CronParser unixParser;
    private final CronParser quartzParser;

    public ScheduleManager(ScheduledCommandsPlugin plugin) {
        this.plugin = plugin;
        this.unixParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(UNIX));
        this.quartzParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(QUARTZ));
    }

    public void loadAndStart() {
        // Clear previous state
        shutdown();
        joinSchedules.clear();

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("schedules");
        if (section == null) {
            plugin.getLogger().warning("No 'schedules' section found in config.yml");
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) continue;

            List<String> commands = entry.getStringList("commands");
            if (commands.isEmpty()) {
                plugin.getLogger().warning("Skipping schedule '" + key + "' (no commands)");
                continue;
            }

            String trigger = entry.getString("trigger", "").trim().toLowerCase();
            String cronExpr = entry.getString("cron");

            // Special trigger: player join
            if (trigger.equals("join") || trigger.equals("player_join") || trigger.equals("on_join")) {
                joinSchedules.add(new JoinSchedule(key, commands));
                plugin.getLogger().info("Loaded join schedule '" + key + "' (" + commands.size() + " command(s))");
                continue;
            }

            // Cron-based schedule
            if (cronExpr == null || cronExpr.isBlank()) {
                plugin.getLogger().warning("Skipping schedule '" + key + "' (missing cron or valid trigger)");
                continue;
            }

            Cron cron = parseCron(cronExpr);
            if (cron == null) {
                plugin.getLogger().warning("Invalid cron expression for '" + key + "': " + cronExpr);
                continue;
            }

            scheduleNext(key, cron, commands);
            plugin.getLogger().info("Loaded cron schedule '" + key + "': " + cronExpr);
        }

        // Register listener only if we have join schedules
        if (!joinSchedules.isEmpty()) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (JoinSchedule schedule : joinSchedules) {
            runCommands(schedule.name(), schedule.commands(), player);
        }
    }

    private Cron parseCron(String expression) {
        String trimmed = expression.trim();
        try {
            int fields = trimmed.split("\\s+").length;
            if (fields >= 6) {
                return quartzParser.parse(trimmed);
            } else {
                return unixParser.parse(trimmed);
            }
        } catch (Exception e) {
            try {
                return unixParser.parse(trimmed);
            } catch (Exception ignored) {
                try {
                    return quartzParser.parse(trimmed);
                } catch (Exception ignored2) {
                    return null;
                }
            }
        }
    }

    private void scheduleNext(String key, Cron cron, List<String> commands) {
        ExecutionTime executionTime = ExecutionTime.forCron(cron);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        Optional<ZonedDateTime> next = executionTime.nextExecution(now);

        if (next.isEmpty()) {
            plugin.getLogger().warning("Could not calculate next execution for '" + key + "'");
            return;
        }

        long delayTicks = Math.max(1, (next.get().toEpochSecond() - now.toEpochSecond()) * 20);

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            runCommands(key, commands, null);
            scheduleNext(key, cron, commands);
        }, delayTicks);

        BukkitTask old = runningTasks.put(key, task);
        if (old != null) {
            old.cancel();
        }
    }

    private void runCommands(String key, List<String> commands, Player player) {
        plugin.getLogger().info("Running schedule '" + key + "' (" + commands.size() + " command(s))"
                + (player != null ? " for " + player.getName() : ""));
        for (String cmd : commands) {
            if (cmd == null || cmd.isBlank()) continue;
            String processed = cmd;
            if (player != null) {
                processed = processed
                        .replace("%player%", player.getName())
                        .replace("%uuid%", player.getUniqueId().toString());
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
        }
    }

    public void shutdown() {
        for (BukkitTask task : runningTasks.values()) {
            task.cancel();
        }
        runningTasks.clear();
        // Listener is automatically cleaned up when plugin disables;
        // on reload we just clear the list so no more join actions fire.
        joinSchedules.clear();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    private record JoinSchedule(String name, List<String> commands) {}
}
