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
import uk.globeworks.api.GenericHistoryEvent;
import uk.globeworks.api.NationRef;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.cronutils.model.CronType.QUARTZ;
import static com.cronutils.model.CronType.UNIX;

public class ScheduleManager implements Listener {

    private final ScheduledCommandsPlugin plugin;
    private final Map<String, BukkitTask> runningTasks = new HashMap<>();
    private final List<JoinSchedule> joinSchedules = new ArrayList<>();
    private final List<GlobeworksSchedule> globeworksSchedules = new ArrayList<>();
    private final CronParser unixParser;
    private final CronParser quartzParser;
    private boolean globeworksAvailable;

    public ScheduleManager(ScheduledCommandsPlugin plugin) {
        this.plugin = plugin;
        this.unixParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(UNIX));
        this.quartzParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(QUARTZ));
    }

    public void loadAndStart() {
        shutdown();
        joinSchedules.clear();
        globeworksSchedules.clear();

        globeworksAvailable = Bukkit.getPluginManager().getPlugin("GlobeworksAPI") != null
            && Bukkit.getPluginManager().isPluginEnabled("GlobeworksAPI");

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

            String trigger = entry.getString("trigger", "").trim().toLowerCase(Locale.ROOT);
            String cronExpr = entry.getString("cron");

            // Player join
            if (trigger.equals("join") || trigger.equals("player_join") || trigger.equals("on_join")) {
                joinSchedules.add(new JoinSchedule(key, commands));
                plugin.getLogger().info("Loaded join schedule '" + key + "' (" + commands.size() + " command(s))");
                continue;
            }

            // Globeworks GenericHistoryEvent
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
                plugin.getLogger().info("Loaded globeworks schedule '" + key + "' types=" + types
                    + " (" + commands.size() + " command(s))");
                continue;
            }

            // Cron-based
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

        boolean needListener = !joinSchedules.isEmpty() || !globeworksSchedules.isEmpty();
        if (needListener) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
    }

    private static boolean isGlobeworksTrigger(String trigger) {
        return trigger.equals("globeworks")
            || trigger.equals("gw")
            || trigger.equals("history")
            || trigger.equals("generic_history")
            || trigger.equals("event");
    }

    /**
     * event-type: single string, or event-types: list.
     * Supports trailing * wildcards, e.g. diplomacy.*
     */
    private Set<String> resolveEventTypes(ConfigurationSection entry) {
        Set<String> types = new HashSet<>();
        String single = entry.getString("event-type");
        if (single != null && !single.isBlank()) {
            types.add(single.trim());
        }
        List<String> list = entry.getStringList("event-types");
        for (String t : list) {
            if (t != null && !t.isBlank()) {
                types.add(t.trim());
            }
        }
        return types;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (JoinSchedule schedule : joinSchedules) {
            runCommands(schedule.name(), schedule.commands(), player, Map.of());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlobeworksHistory(GenericHistoryEvent event) {
        if (globeworksSchedules.isEmpty()) {
            return;
        }
        String type = event.getEventType();
        if (type == null) {
            return;
        }

        Map<String, String> placeholders = buildPlaceholders(event);

        for (GlobeworksSchedule schedule : globeworksSchedules) {
            if (!matchesEventType(schedule.eventTypes(), type)) {
                continue;
            }
            // Prefer online actor for %player% substitution when present
            Player actor = firstOnlineActor(event);
            runCommands(schedule.name(), schedule.commands(), actor, placeholders);
        }
    }

    private static boolean matchesEventType(Set<String> patterns, String eventType) {
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

    private Map<String, String> buildPlaceholders(GenericHistoryEvent event) {
        Map<String, String> map = new HashMap<>();
        map.put("eventType", nullToEmpty(event.getEventType()));
        map.put("eventId", event.getEventId() != null ? event.getEventId().toString() : "");

        NationRef nation = event.getNation();
        if (nation != null) {
            map.put("nation", nullToEmpty(strip(nation.cachedName())));
            map.put("nationId", nation.id() != null ? nation.id().toString() : "");
        } else {
            map.put("nation", "");
            map.put("nationId", "");
        }

        Map<String, String> payload = event.getPayload();
        if (payload != null) {
            for (Map.Entry<String, String> e : payload.entrySet()) {
                if (e.getKey() != null) {
                    map.put(e.getKey(), nullToEmpty(strip(e.getValue())));
                }
            }
            // Common aliases
            if (payload.containsKey("otherNation") && !map.containsKey("otherNation")) {
                map.put("otherNation", nullToEmpty(strip(payload.get("otherNation"))));
            }
        }
        map.putIfAbsent("otherNation", "");

        List<UUID> actors = event.getActorIds();
        if (actors != null && !actors.isEmpty()) {
            UUID first = actors.get(0);
            map.put("uuid", first.toString());
            Player p = Bukkit.getPlayer(first);
            map.put("player", p != null ? p.getName() : first.toString());
        } else {
            map.putIfAbsent("uuid", "");
            map.putIfAbsent("player", "");
        }
        return map;
    }

    private Player firstOnlineActor(GenericHistoryEvent event) {
        List<UUID> actors = event.getActorIds();
        if (actors == null) {
            return null;
        }
        for (UUID id : actors) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                return p;
            }
        }
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String strip(String input) {
        if (input == null) {
            return null;
        }
        return input.replaceAll("(?i)[§&][0-9a-fk-orx]", "")
            .replace('_', ' ')
            .replaceAll("\\s+", " ")
            .trim();
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
            runCommands(key, commands, null, Map.of());
            scheduleNext(key, cron, commands);
        }, delayTicks);

        BukkitTask old = runningTasks.put(key, task);
        if (old != null) {
            old.cancel();
        }
    }

    private void runCommands(String key, List<String> commands, Player player, Map<String, String> extra) {
        plugin.getLogger().info("Running schedule '" + key + "' (" + commands.size() + " command(s))"
            + (player != null ? " for " + player.getName() : ""));
        for (String cmd : commands) {
            if (cmd == null || cmd.isBlank()) continue;
            String processed = applyPlaceholders(cmd, player, extra);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
        }
    }

    private String applyPlaceholders(String cmd, Player player, Map<String, String> extra) {
        String processed = cmd;
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                processed = processed.replace("%" + e.getKey() + "%", e.getValue() != null ? e.getValue() : "");
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
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    private record JoinSchedule(String name, List<String> commands) {}

    private record GlobeworksSchedule(String name, Set<String> eventTypes, List<String> commands) {}
}
