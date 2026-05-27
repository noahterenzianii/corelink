# CoreLink

CoreLink is a **Fabric mod** that brings a **shared coordinate management system** to Minecraft servers. Players can save named locations, look them up, and get a real-time heads-up display arrow pointing to any coordinate or online player — no more alt-tabbing to check coordinates.

All data is persisted using **SQLite** and shared across every player on the server, making it easy to collaborate on building projects, mark points of interest, or navigate to friends.

Coordinate **names are case-insensitive**: `Home`, `home`, and `HOME` all refer to the same location.

## Commands

### `/coord set <name> [description]`
Saves your current position with a name and an optional description. Names must be a single word. If a coordinate with the same name (ignoring case) already exists, it will be overwritten.

### `/coord list`
Lists every saved coordinate with its position, world, and description. Coordinates are sorted alphabetically.

### `/coord del <name>` or `/coord remove <name>`
Deletes a saved coordinate by name (case-insensitive).

### `/ping <target> [seconds]`
Shows an **ActionBar arrow** pointing toward the target location or player. The target can be a saved coordinate name or an online player name. The ping lasts 10 seconds by default, up to a maximum of 300 seconds.

- Arrow directions: `↑` `↓` `←` `→` (based on your horizontal look direction)
- Vertical offset shown as `▲ N` or `▼ N` if the target is more than 2 blocks above or below
- Distance in meters is displayed
- The ping ends early if you switch dimensions

### `/ping stop`
Cancels your active ping.

### `/sharedchest`
Opens a **global chest** that every player on the server can access. Items placed inside persist across server restarts. Useful for shared resources, community giveaways, or simple trading.

### `/bot spawn <name> [coordinate]`
Spawns a **fake player** (bot) at your current position, or at a saved coordinate if one is specified. Bot names must be a single word. Bots are visible in-game and can be targeted with `/ping`. Useful for marking temporary points of interest.

Bots are **excluded from sleep percentage calculations** — they don't count as players that need to sleep at night, so they won't prevent the night from being skipped when real players are in bed.

### `/bot kill <name>`
Removes a spawned bot by name.

### `/bot list`
Lists every currently active bot on the server.

## Requirements

- Minecraft **26w** or later
- Fabric Loader **0.19.2** or later
- Fabric API

## Building

```bash
./gradlew build
```

Pre-built JARs are available on the [Releases](https://github.com/anomalyco/corelink/releases) page.

## License

MIT
