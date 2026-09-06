# ScheduledCommands

Simple Paper 26.2 plugin that runs console commands on cron-like schedules **and** on player join.

## Features

- Config-driven schedules using cron expressions (5-field Unix or 6-field with seconds)
- Special `trigger: join` for player join events (cannot be expressed with cron)
- Any number of commands per schedule
- Placeholders on join schedules: `%player%`, `%uuid%`
- Automatically reschedules the next cron run
- `/schedcmds reload` to reload config without restart (permission: `schedcmds.reload`)

## Requirements

- Paper 26.2
- Java 25

## Building

```bash
mvn clean package
```

The shaded JAR will be in `target/ScheduledCommands-1.0.0.jar`.

## Installation

1. Place the JAR in your server's `plugins/` folder
2. Start the server
3. Edit `plugins/ScheduledCommands/config.yml`
4. Run `/schedcmds reload` (or restart)

## Config examples

### Cron schedules
```yaml
schedules:
  daily-reset:
    cron: "0 0 0 * * *"
    commands:
      - "say Daily reset starting..."
      - "time set day"
```

### Player join schedule
```yaml
schedules:
  welcome-on-join:
    trigger: join          # also accepts: player_join, on_join
    commands:
      - "say Welcome to the server, %player%!"
      - "tell %player% Enjoy your stay!"
```

Supported placeholders on join schedules:
- `%player%` → player name
- `%uuid%` → player UUID

Cron expressions use the server's default timezone.
