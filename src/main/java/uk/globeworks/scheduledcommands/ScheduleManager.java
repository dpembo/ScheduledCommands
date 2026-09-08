package uk.globeworks.scheduledcommands;

import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.cronutils.model.CronType.QUARTZ;
import static com.cronutils.model.CronType.UNIX;

public class ScheduleManager implements Listener {

    private final ScheduledCommandsPlugin plugin;
    private final Map<String, BukkitTask> runningTasks = new HashMap<>();
    private final List<JoinSchedule> joinSchedules = new ArrayList<>();
    private final List<GlobeworksSchedule> globeworksSchedules = new ArrayList<>();
    /** All successfully loaded schedules, keyed by name (insertion order preserved). */
    private final Map<String, ScheduleInfo> allSchedules = new LinkedHashMap<>();
    private final CronParser unixParser;
    private final CronParser quartzParser;
    private boolean globeworksAvailable;
    private Listener globeworksListener;
    private long joinDelayTicks = 200L; // 10s default

    public ScheduleManager(ScheduledCommandsPlugin plugin) {
        this.plugin = plugin;
        this.unixParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(UNIX));
        this.quartzParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(QUARTZ));
    }

    public void loadAndStart() {
        shutdown();
        joinSchedules.clear();
        globeworksSchedules.clear();
        allSchedules.clear();

        globeworksAvailable = Bukkit.getPluginManager().getPlugin("GlobeworksAPI") != null
            && Bukkit.getPluginManager().isPluginEnabled("GlobeworksAPI");

        // Seconds after join before running join schedules (non-blocking)
        double joinDelaySec = plugin.getConfig().getDouble("join-delay-seconds", 10.0);
        if (joinDelaySec < 0) {
            joinDelaySec = 0;
        }
        this.joinDelayTicks = Math.max(0L, Math.round(joinDelaySec * 20.0));
        plugin.getLogger().info("Join command delay: " + joinDelaySec + "s (" + joinDelayTicks + " ticks)");

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("schedules");
        if (section == null) {
            plugin.getLogger().warning("No 'schedules' section found in config.yml");
            Bukkit.getPluginManager().registerEvents(this, plugin);
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) continue;

            List<String> commands = readCommands(entry);
            if (commands.isEmpty()) {
                plugin.getLogger().warning("Skipping schedule '" + key
                    + "' (no commands — use a YAML list under commands:)");
                continue;
            }

            String trigger = entry.getString("trigger", "").trim().toLowerCase(Locale.ROOT);
            String cronExpr = entry.getString("cron");

            if (trigger.equals("join") || trigger.equals("player_join") || trigger.equals("on_join")) {
                joinSchedules.add(new JoinSchedule(key, commands));
                allSchedules.put(key, new ScheduleInfo(key, ScheduleType.JOIN, "on player join", commands));
                plugin.getLogger().info("Loaded join schedule '" + key + "' (" + commands.size() + " command(s))");
                continue;
            }

            if (isGlobeworksTrigger(trigger)) {
                if (!globeworksAvailable) {
                    plugin.getLogger().warning("Skipping schedule '" + key
                        + "' (trigger requires GlobeworksAPI, which is not present)");
                    continue;
                }
                Set<String> types = resolveEventTypes(entry);
                if (types.isEmpty()) {
                    plugin.getLogger().warning("Skipping schedule '" + key
                        + "' (globeworks trigger needs event-type or event-types)");
                    continue;
                }
                globeworksSchedules.add(new GlobeworksSchedule(key, types, commands));
                String when = "on globeworks: " + String.join(", ", types);
                allSchedules.put(key, new ScheduleInfo(key, ScheduleType.GLOBEWORKS, when, commands));
                plugin.getLogger().info("Loaded globeworks schedule '" + key + "' types=" + types
                    + " (" + commands.size() + " command(s))");
                continue;
            }

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
            allSchedules.put(key, new ScheduleInfo(key, ScheduleType.CRON, "cron: " + cronExpr.trim(), commands));
            plugin.getLogger().info("Loaded cron schedule '" + key + "': " + cronExpr);
        }

        // Join listener — no dependency on GlobeworksAPI classes
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("Join listener registered (" + joinSchedules.size() + " schedule(s))");

        // Globeworks listener in a separate class (avoids classloader issues on this listener)
        if (globeworksAvailable && !globeworksSchedules.isEmpty()) {
            try {
                globeworksListener = new GlobeworksEventListener(
                    plugin,
                    List.copyOf(globeworksSchedules),
                    this::runCommands
                );
                Bukkit.getPluginManager().registerEvents(globeworksListener, plugin);
                plugin.getLogger().info("Globeworks listener registered ("
                    + globeworksSchedules.size() + " schedule(s))");
            } catch (Throwable t) {
                plugin.getLogger().severe("Failed to register Globeworks listener: " + t.getMessage());
                t.printStackTrace();
            }
        }

        plugin.getLogger().info("Schedules ready: "
            + runningTasks.size() + " cron, "
            + joinSchedules.size() + " join, "
            + globeworksSchedules.size() + " globeworks");
    }

    private List<String> readCommands(ConfigurationSection entry) {
        List<String> list = entry.getStringList("commands");
        if (list != null && !list.isEmpty()) {
            return list;
        }
        String single = entry.getString("commands");
        if (single != null && !single.isBlank()) {
            return List.of(single.trim());
        }
        return List.of();
    }

    private static boolean isGlobeworksTrigger(String trigger) {
        return trigger.equals("globeworks")
            || trigger.equals("gw")
            || trigger.equals("history")
            || trigger.equals("generic_history")
            || trigger.equals("event");
    }

    private Set<String> resolveEventTypes(ConfigurationSection entry) {
        Set<String> types = new HashSet<>();
        String single = entry.getString("event-type");
        if (single != null && !single.isBlank()) {
            types.add(single.trim());
        }
        for (String t : entry.getStringList("event-types")) {
            if (t != null && !t.isBlank()) {
                types.add(t.trim());
            }
        }
        return types;
    }

    static boolean matchesEventType(Set<String> patterns, String eventType) {
        for (String pattern : patterns) {
            if (pattern.equals("*") || pattern.equalsIgnoreCase(eventType)) {
                return true;
            }
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (eventType.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (joinSchedules.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        final java.util.UUID uuid = player.getUniqueId();
        final String name = player.getName();
        final List<JoinSchedule> toRun = List.copyOf(joinSchedules);

        plugin.getLogger().info("Player join: " + name
            + " — " + toRun.size() + " join schedule(s) in "
            + (joinDelayTicks / 20.0) + "s");

        Runnable run = () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                plugin.getLogger().info("Skipping join schedules for " + name + " (left before delay elapsed)");
                return;
            }
            for (JoinSchedule schedule : toRun) {
                runCommands(schedule.name(), schedule.commands(), online, Map.of());
            }
        };

        if (joinDelayTicks <= 0) {
            run.run();
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, run, joinDelayTicks);
        }
    }

    private Cron parseCron(String expression) {
        String trimmed = expression.trim();
        String[] parts = trimmed.split("\\s+");
        int fields = parts.length;

        String quartzExpr = trimmed;
        if (fields >= 6) {
            quartzExpr = toQuartzCompatible(parts);
        }

        if (fields >= 6) {
            try {
                return quartzParser.parse(quartzExpr);
            } catch (Exception e) {
                plugin.getLogger().warning("Quartz parse failed for '" + quartzExpr
                    + "' (from '" + trimmed + "'): " + e.getMessage());
            }
            try {
                return quartzParser.parse(trimmed);
            } catch (Exception ignored) {
            }
        }

        try {
            return unixParser.parse(trimmed);
        } catch (Exception e) {
            try {
                return quartzParser.parse(toQuartzCompatible(
                    fields >= 6 ? parts : (trimmed + " *").trim().split("\\s+")));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String toQuartzCompatible(String[] parts) {
        if (parts == null || parts.length < 6) {
            return parts == null ? "" : String.join(" ", parts);
        }
        String[] p = java.util.Arrays.copyOf(parts, 6);
        if ("*".equals(p[3]) && "*".equals(p[5])) {
            p[5] = "?";
        }
        return String.join(" ", p);
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
            runCommands(key, commands, null, Map.of());
            scheduleNext(key, cron, commands);
        }, delayTicks);

        BukkitTask old = runningTasks.put(key, task);
        if (old != null) {
            old.cancel();
        }
    }

    void runCommands(String key, List<String> commands, Player player, Map<String, String> extra) {
        plugin.getLogger().info("Running schedule '" + key + "' (" + commands.size() + " command(s))"
            + (player != null ? " for " + player.getName() : ""));
        for (String cmd : commands) {
            if (cmd == null || cmd.isBlank()) continue;
            String processed = applyPlaceholders(cmd, player, extra);
            plugin.getLogger().info("  -> /" + processed);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
        }
    }

    private String applyPlaceholders(String cmd, Player player, Map<String, String> extra) {
        String processed = cmd;
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                processed = processed.replace("%" + e.getKey() + "%",
                    e.getValue() != null ? e.getValue() : "");
            }
        }
        if (player != null) {
            processed = processed
                .replace("%player%", player.getName())
                .replace("%uuid%", player.getUniqueId().toString());
        }
        return processed;
    }

    public void shutdown() {
        for (BukkitTask task : runningTasks.values()) {
            task.cancel();
        }
        runningTasks.clear();
        joinSchedules.clear();
        globeworksSchedules.clear();
        allSchedules.clear();
        org.bukkit.event.HandlerList.unregisterAll(this);
        if (globeworksListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(globeworksListener);
            globeworksListener = null;
        }
    }

    /** Returns an unmodifiable view of all loaded schedules (name → info). */
    public Map<String, ScheduleInfo> getSchedules() {
        return Collections.unmodifiableMap(allSchedules);
    }

    /**
     * Manually run a schedule by name (op/test use).
     * Executes the commands as console with no player context and no extra placeholders.
     *
     * @return true if the schedule existed and was executed
     */
    public boolean runScheduleNow(String key) {
        ScheduleInfo info = allSchedules.get(key);
        if (info == null) {
            // case-insensitive fallback
            for (Map.Entry<String, ScheduleInfo> e : allSchedules.entrySet()) {
                if (e.getKey().equalsIgnoreCase(key)) {
                    info = e.getValue();
                    break;
                }
            }
        }
        if (info == null) {
            return false;
        }
        runCommands(info.name(), info.commands(), null, Map.of());
        return true;
    }

    private record JoinSchedule(String name, List<String> commands) {}

    /** Public so GlobeworksEventListener can use it. */
    public record GlobeworksSchedule(String name, Set<String> eventTypes, List<String> commands) {}

    public enum ScheduleType {
        CRON, JOIN, GLOBEWORKS
    }

    /** Snapshot of a loaded schedule for listing / testing. */
    public record ScheduleInfo(
        String name,
        ScheduleType type,
        String whenDescription,
        List<String> commands
    ) {
        public int commandCount() {
            return commands == null ? 0 : commands.size();
        }
    }
}