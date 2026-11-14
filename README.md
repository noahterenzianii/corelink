# CoreLink

CoreLink is a **Fabric mod** that brings a shared coordinate management system to Minecraft servers. Players can save named locations, look them up, and get a heads-up display arrow pointing to any coordinate or online player.

All data is persisted using **SQLite** and shared across every player on the server. Coordinate and bot names are case-insensitive. Coordinate names are unique — delete a coordinate before reusing its name.

Bots live in the database too: their per-player files (playerdata, stats, advancements) are removed whenever a bot is killed or the server stops, and leftovers from CoreLink 1.1.0 are cleaned up on startup. Bot UUIDs use a dedicated `corelink-bot:` namespace, so they can never collide with real players on offline-mode servers.

## Commands

| Command | Description |
|---|---|
| `/coord set <name> [description]` | Save current position with an optional description |
| `/coord list` | List every saved coordinate |
| `/coord del <name>` (alias: `/coord remove`) | Delete a saved coordinate |
| `/ping <target> [seconds]` | HUD arrow pointing to a coordinate or player (default 10s, max 300s) |
| `/ping stop` | Cancel active ping |
| `/sharedchest` | Open a global chest accessible to all players |
| `/bot spawn <name> [coordinate]` | Spawn a fake player at your position or a saved coordinate |
| `/bot kill <name>` | Remove a spawned bot |
| `/bot list` | List all active bots |

Every command is also available under the `/corelink:` namespace — for example `/corelink:ping` — to avoid conflicts with other mods.

## Requirements

- Minecraft **26.1.2** (26.1.x)
- Fabric Loader **0.19.2** or later
- Java **25** or later
- No extra dependencies: the server JAR bundles Fabric API and the SQLite driver. Only the *client companion* needs Fabric API in your mods folder.

## Client Companion (optional)

`corelink-client` is a tiny companion mod for players: it adds a keybinding (**Options → Controls → CoreLink → Open Shared Chest**, unbound by default) that sends the server-side `/sharedchest` command.

It contains no game logic — bots, coordinates, the shared chest and the database live only in the CoreLink **server** mod. Without CoreLink on the server, the keybind simply reports an unknown command.

## Building

```bash
./gradlew build
```

Pre-built JARs are available on the [Releases](https://github.com/noahterenzianii/corelink/releases) page.

## License

MIT
