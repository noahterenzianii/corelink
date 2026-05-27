# CoreLink

CoreLink is a **Fabric mod** that brings a shared coordinate management system to Minecraft servers. Players can save named locations, look them up, and get a heads-up display arrow pointing to any coordinate or online player.

All data is persisted using **SQLite** and shared across every player on the server. Coordinate names are case-insensitive.

## Commands

| Command | Description |
|---|---|
| `/coord set <name> [description]` | Save current position with an optional description |
| `/coord list` | List every saved coordinate |
| `/coord del <name>` | Delete a saved coordinate |
| `/ping <target> [seconds]` | HUD arrow pointing to a coordinate or player (default 10s, max 300s) |
| `/ping stop` | Cancel active ping |
| `/sharedchest` | Open a global chest accessible to all players |
| `/bot spawn <name> [coordinate]` | Spawn a fake player at your position or a saved coordinate |
| `/bot kill <name>` | Remove a spawned bot |
| `/bot list` | List all active bots |

## Requirements

- Minecraft **26w** or later
- Fabric Loader **0.19.2** or later
- Fabric API

## Building

```bash
./gradlew build
```

Pre-built JARs are available on the [Releases](https://github.com/noahterenzianii/corelink/releases) page.

## License

MIT
