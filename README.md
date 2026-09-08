# ScheduledCommands

Paper plugin that runs **console commands** when something happens:

|Trigger|When it runs|
|-|-|
|**Cron**|On a time schedule (server timezone)|
|**Join**|When a player joins the server|
|**Globeworks**|When a `GenericHistoryEvent` is fired (Diplomacy, Towny, SiegeWar, LuckPerms, …)|

Each schedule has a unique key under `schedules:` and a list of commands. Commands are always run as the **console**.

\---

## Requirements

|Dependency|Required?|Notes|
|-|-|-|
|Paper **26.2** (Java **25**)|Yes||
|**GlobeworksAPI**|Soft|Only needed for `trigger: globeworks` schedules|

Producers of Globeworks events (not part of this plugin):

* **GlobeworksDiplomacy** — diplomacy.\* events
* **GlobeworksBridges** — towny.*, siegewar.*, luckperms.\* events
* Any other plugin that calls `Bukkit.getPluginManager().callEvent(GenericHistoryEvent…)`

\---

## Installation

1. Build (see below) or drop the jar into `plugins/`
2. Start the server once to generate `plugins/ScheduledCommands/config.yml`
3. Edit the config
4. Run `/schedcmds reload` (or restart)

\---

## Building

```bash
# Install GlobeworksAPI into the local Maven repo (compile-only)
mvn install:install-file -Dfile=libs/globeworks-api-1.0.0.jar \\
  -DgroupId=uk.globeworks -DartifactId=globeworks-api -Dversion=1.0.0 -Dpackaging=jar

mvn clean package
```

Output: `target/ScheduledCommands-1.1.0.jar` (cron-utils is shaded in).

\---

## Commands & permissions

|Command|Permission|Default|Description|
|-|-|-|-|
|`/schedcmds reload`|`schedcmds.reload`|op|Reload `config.yml` and reschedule everything|
|`/schedcmds list`|`schedcmds.list`|op|List loaded schedules (name, when it runs, command count)|
|`/schedcmds test <name>`|`schedcmds.test`|op|Manually run a schedule’s commands as console (for testing)|
|`/schedcmds debug [on\|off\|toggle\|status]`|`schedcmds.debug`|op|Toggle debug logging for schedule activation|

Aliases: `list` also accepts `ls`; `test` also accepts `run` / `execute`. With no argument, `debug` toggles.

`/schedcmds test` executes the schedule’s commands immediately as the console with **no player context** and **no event placeholders** (any `%player%` / `%nation%` etc. are left unsubstituted). Use it to verify command syntax without waiting for the real trigger.

When **debug** is on (config key `debug: true`, or toggled in-game), the console logs each schedule activation and every command dispatched. Load/reload messages are always shown.

---

## Configuration overview

All schedules live under one root key:

```yaml
schedules:
  my-schedule-key:
    # ONE of: cron  OR  trigger: join  OR  trigger: globeworks
    commands:
      - "say Hello"
      - "another command here"
```

### Common fields

|Field|Type|Required|Description|
|-|-|-|-|
|`commands`|list of strings|**Yes**|Console commands to run, in order. Empty list → schedule is skipped.|
|`cron`|string|For time-based schedules|Cron expression (see below).|
|`trigger`|string|For join / globeworks|Which non-cron trigger to use.|
|`event-type`|string|Globeworks (one of)|Single event type or wildcard pattern.|
|`event-types`|list of strings|Globeworks (one of)|Multiple types/patterns.|

A schedule must have **either** a valid `cron` **or** a recognised `trigger`. If both are set, **trigger wins** for join/globeworks; otherwise cron is used.

Schedule keys (`daily-reset`, `announce-war`, …) are labels for logging only; they must be unique under `schedules:`.

\---

## 1\. Cron schedules

```yaml
schedules:
  daily-reset:
    cron: "0 0 0 \* \* \*"
    commands:
      - "say Daily reset starting..."
      - "time set day"
      - "weather clear"

  every-5-minutes:
    cron: "0 \*/5 \* \* \* \*"
    commands:
      - "say Five minutes have passed."
```

### Cron format

|Fields|Pattern|Example|
|-|-|-|
|**5** (Unix)|`min hour day-of-month month day-of-week`|`0 0 \* \* \*` → midnight every day|
|**6** (Quartz-style)|`sec min hour day-of-month month day-of-week`|`0 0 0 \* \* \*` → midnight; `0 \*/15 \* \* \* \*` → every 15 minutes|

Examples:

|Expression|Meaning|
|-|-|
|`0 0 \* \* \*`|Every day at 00:00 (5-field)|
|`0 0 0 \* \* \*`|Every day at 00:00:00 (6-field)|
|`0 \*/15 \* \* \* \*`|Every 15 minutes|
|`0 30 8 \* \* 1-5`|08:30 on weekdays|
|`0 0 12 1 \* \*`|Noon on the 1st of each month|

**Timezone:** the JVM / server default timezone (not configurable in this plugin).

After each run, the next execution is calculated and rescheduled automatically.

**Placeholders on cron schedules:** none (unless you hard-code names in the command strings).

\---

## 2\. Player join schedules

```yaml
schedules:
  welcome-on-join:
    trigger: join          # also: player\_join, on\_join
    commands:
      - "say Welcome to the server, %player%!"
      - "tell %player% Enjoy your stay!"
```

### Trigger aliases

|Value|Meaning|
|-|-|
|`join`|Player join|
|`player\_join`|Same|
|`on\_join`|Same|

### Placeholders (join)

|Placeholder|Value|
|-|-|
|`%player%`|Joining player’s name|
|`%uuid%`|Joining player’s UUID|

Runs once per join, for every loaded join schedule.

\---

## 3\. Globeworks event schedules

Requires **GlobeworksAPI** enabled. If it is missing, these schedules are skipped and a warning is logged at load time.

```yaml
schedules:
  announce-war:
    trigger: globeworks
    event-type: diplomacy.war\_declared
    commands:
      - "say §cWar! §f%nation% has declared war on %otherNation%!"

  announce-alliance:
    trigger: globeworks
    event-types:
      - diplomacy.alliance\_formed
      - diplomacy.alliance\_broken
    commands:
      - "say §eDiplomacy: §f%nation% / %otherNation% — %eventType%"

  any-diplomacy:
    trigger: globeworks
    event-type: diplomacy.\*
    commands:
      - "say %eventType%: %nation%"
```

### Trigger aliases

|Value|Meaning|
|-|-|
|`globeworks`|Preferred|
|`gw`|Same|
|`history`|Same|
|`event`|Same|
|`generic\_history`|Same|

### Matching event types

Use **one** of:

```yaml
event-type: diplomacy.war\_declared
```

```yaml
event-types:
  - diplomacy.war\_declared
  - diplomacy.peace\_accepted
```

|Pattern|Matches|
|-|-|
|`diplomacy.war\_declared`|Exact type only|
|`diplomacy.\*`|Any type starting with `diplomacy.`|
|`\*`|Every Globeworks history event|

Matching is case-insensitive for exact strings; prefix wildcards only support a trailing `\*`.

### Built-in placeholders (globeworks)

|Placeholder|Source|
|-|-|
|`%eventType%`|Event type string (e.g. `diplomacy.war\_declared`)|
|`%eventId%`|Event id string|
|`%nation%`|Primary nation display name (formatting stripped)|
|`%nationId%`|Primary nation UUID|
|`%otherNation%`|From payload when present (often the other nation)|
|`%player%`|First actor’s name if online, else UUID string if known|
|`%uuid%`|First actor UUID if any|

### Payload placeholders

Any key on the event payload becomes `%key%`. Common examples:

|Event family|Typical keys|
|-|-|
|Diplomacy|`otherNation`, `otherNationId`, `state`|
|Towny|`townName`, `townId`, `player`, `townResidents`, `nationResidents`, `count`|
|SiegeWar|`townName`, `siegeType`, `attacker`, `defender`, `winner`|
|LuckPerms|`player`, `playerId`, `group`, `groupId`, `action`|

Unknown keys simply leave `%key%` unsubstituted if that key was not on the event.

### Example event types (from ecosystem plugins)

**Diplomacy**

* `diplomacy.war\_declared`
* `diplomacy.alliance\_formed` / `diplomacy.alliance\_broken`
* `diplomacy.peace\_accepted` / `diplomacy.peace\_proposed`
* `diplomacy.alliance\_proposed` / `diplomacy.alliance\_denied` / `diplomacy.alliance\_proposal\_cancelled`
* `diplomacy.relation\_cleared`

**Towny (GlobeworksBridges)**

* `towny.nation\_founded` / `towny.nation\_disbanded`
* `towny.town\_founded` / `towny.town\_disbanded`
* `towny.resident\_joined\_town`
* `towny.town\_population\_milestone` / `towny.nation\_population\_milestone`

**SiegeWar (GlobeworksBridges)**

* `siegewar.siege\_started` / `siegewar.siege\_ended`

**LuckPerms (GlobeworksBridges)**

* `luckperms.rank\_granted` / `luckperms.rank\_removed`

\---

## Full example config

```yaml
schedules:
  daily-reset:
    cron: "0 0 0 \* \* \*"
    commands:
      - "say Daily reset starting..."
      - "time set day"

  welcome-on-join:
    trigger: join
    commands:
      - "tell %player% Welcome!"

  announce-war:
    trigger: globeworks
    event-type: diplomacy.war\_declared
    commands:
      - "say §cWar! §f%nation% has declared war on %otherNation%!"

  announce-siege:
    trigger: globeworks
    event-type: siegewar.siege\_started
    commands:
      - "say §6Siege: §f%nation% is attacking %townName%!"

  announce-rank:
    trigger: globeworks
    event-types:
      - luckperms.rank\_granted
      - luckperms.rank\_removed
    commands:
      - "say §b%player% §f— %eventType% (%group%)"
```

\---

## Behaviour notes

* Commands run on the **main thread** as **console** via `Bukkit.dispatchCommand`.
* Cron tasks are cancelled and rebuilt on `/schedcmds reload` and on disable.
* Join and Globeworks listeners are re-registered on reload.
* Globeworks handlers use priority **MONITOR** and ignore cancelled events.
* If GlobeworksAPI is not installed, non-globeworks schedules still work.

\---

## Troubleshooting

|Symptom|Check|
|-|-|
|Globeworks schedules not loading|Is GlobeworksAPI present and enabled? Look for a skip warning in the log.|
|Schedule never fires|Event type string must match producers (e.g. `diplomacy.war\_declared`). Try `event-type: "\*"` temporarily.|
|Empty `%nation%` / `%player%`|Event may have no nation or no actors; use payload keys the producer actually sets.|
|Cron off by hours|Server JVM timezone vs what you expect.|
|Commands do nothing|Test the same command from the console; ensure no leading `/` unless your setup requires it (Paper usually wants commands **without** a leading slash for `dispatchCommand`).|

\---

## License

See [LICENSE](LICENSE) in this repository.

