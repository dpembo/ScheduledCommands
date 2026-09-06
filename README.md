# ScheduledCommands

Paper plugin that runs console commands on:

- **Cron** schedules
- **Player join**
- **Globeworks** `GenericHistoryEvent` (Diplomacy, Bridges, etc.)

## Requirements

- Paper 26.2 / Java 25
- **GlobeworksAPI** (soft) — only needed for `trigger: globeworks` schedules

## Build

```bash
mvn install:install-file -Dfile=libs/globeworks-api-1.0.0.jar \
  -DgroupId=uk.globeworks -DartifactId=globeworks-api -Dversion=1.0.0 -Dpackaging=jar
mvn clean package
```

## Globeworks triggers

```yaml
schedules:
  announce-war:
    trigger: globeworks
    event-type: diplomacy.war_declared
    commands:
      - "say War! %nation% declared war on %otherNation%!"

  diplomacy-any:
    trigger: globeworks
    event-type: diplomacy.*
    commands:
      - "say %eventType%: %nation% / %otherNation%"
```

### Placeholders (globeworks)

| Placeholder | Source |
|-------------|--------|
| `%eventType%` | Event type string |
| `%eventId%` | Event UUID |
| `%nation%` / `%nationId%` | Primary nation |
| `%otherNation%` | From payload when present |
| `%player%` / `%uuid%` | First actor (if any) |
| `%…%` | Any payload key (`%group%`, `%townName%`, `%count%`, …) |

Events are produced by **Diplomacy** and **GlobeworksBridges**, not by this plugin.

## Commands

`/schedcmds reload` — reload config (`schedcmds.reload`, default op)
