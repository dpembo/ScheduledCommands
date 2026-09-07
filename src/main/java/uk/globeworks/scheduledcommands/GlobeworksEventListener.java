package uk.globeworks.scheduledcommands;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import uk.globeworks.api.GenericHistoryEvent;
import uk.globeworks.api.NationRef;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Separate listener so ScheduleManager can register join handlers even when
 * GlobeworksAPI classloading would otherwise interfere.
 */
public final class GlobeworksEventListener implements Listener {

    private final ScheduledCommandsPlugin plugin;
    private final List<ScheduleManager.GlobeworksSchedule> schedules;
    private final BiConsumer<String, List<String>> /* unused */ ignored;
    private final CommandRunner runner;

    @FunctionalInterface
    public interface CommandRunner {
        void run(String scheduleName, List<String> commands, Player player, Map<String, String> placeholders);
    }

    public GlobeworksEventListener(ScheduledCommandsPlugin plugin,
                                   List<ScheduleManager.GlobeworksSchedule> schedules,
                                   CommandRunner runner) {
        this.plugin = plugin;
        this.schedules = schedules;
        this.runner = runner;
        this.ignored = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlobeworksHistory(GenericHistoryEvent event) {
        if (schedules == null || schedules.isEmpty()) {
            return;
        }
        String type = event.getEventType();
        if (type == null) {
            return;
        }

        Map<String, String> placeholders = buildPlaceholders(event);
        Player actor = firstOnlineActor(event);

        for (ScheduleManager.GlobeworksSchedule schedule : schedules) {
            if (!ScheduleManager.matchesEventType(schedule.eventTypes(), type)) {
                continue;
            }
            runner.run(schedule.name(), schedule.commands(), actor, placeholders);
        }
    }

    private Map<String, String> buildPlaceholders(GenericHistoryEvent event) {
        Map<String, String> map = new HashMap<>();
        map.put("eventType", nullToEmpty(event.getEventType()));
        map.put("eventId", event.getEventId() != null ? event.getEventId() : "");

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
}