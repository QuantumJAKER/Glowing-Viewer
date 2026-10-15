# Glowing Viewer

Selective glowing for **Minecraft Forge 1.20.1** – **server-side only**.
Selected entities glow only for selected players (similar to the target
selector for particles). Inspired by
[Selective Glowing (Fabric)](https://modrinth.com/mod/selective-glowing),
reimplemented as a Forge mod.

> **Status:** Work in progress (`0.1.3`). The mod compiles, builds, and loads
> on a dedicated server, but has not been verified in-game with real players
> yet.

Vanilla clients can join without installing the mod: only vanilla packets
are sent.

## Requirements

- Minecraft 1.20.1 with Forge 47.x (server)
- To build: JDK 17 (playing on the server just needs a regular Java 17+)

## Installation

1. Put `glowingviewer-<version>.jar` into the server's `mods/` folder.
2. Start the server. Clients do **not** need the mod.

## Commands

Permission level 2 (like `/effect`). All arguments are vanilla types, so the
commands also work with `execute`, command blocks, and datapacks.

```mcfunction
# Duration in seconds is required (1–1000000)
/glow give <targets> <duration> <viewers>

# Remove: for everyone or only for specific viewers
/glow clear <targets> [viewers]
```

`<targets>` are entities (`@e`, `@s`, …), `<viewers>` are players.
Examples:

```mcfunction
/glow give @e[type=zombie] 30 @a[team=Red]
execute as @a at @s run glow give @e[distance=..10] 10 @s
/glow clear @e
```

## Behavior

- **Additive:** Real vanilla glowing (effect, spectral arrow) stays visible
  to everyone. The mod adds extra per-viewer glowing instead of removing
  anything.
- **Team colors untouched:** The outline color comes from the target's
  scoreboard team, exactly like vanilla. No teams are created or modified –
  existing custom teams keep working unchanged.
- **Duration & restarts:** Every entry has a duration and expires
  automatically. Nothing is saved: a server restart clears all glowing
  overrides.
- **Seeing your own glow:** If you are included in the viewers, you see your
  own glow exactly like with vanilla `/effect` – for others and in
  third-person view. In first-person view no server can draw an outline
  around your own body (the vanilla client doesn't render it), same as
  vanilla.
- **Robustness:** After chunk (re-)loads, logins, respawns, and dimension
  changes, the faked state is re-sent (slightly delayed so vanilla packets
  cannot overtake it). A twice-per-second resync repairs states that genuine
  vanilla updates overwrote in between. All failure paths are defensive:
  glow logic can never crash the server tick or entity tracking.

## Building

JDK 17 is only used for the build process, without any global switch
(example PowerShell – applies to this process only):

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat build
```

The finished jar lands in `build/libs/`. Note: the first build downloads the
Forge toolchain (takes a few minutes).

## Technical overview

- `GlowManager`: in-memory state (`target → viewer → expiry`), packet dispatch.
- `GlowNettyHandler`: per-player pipeline interception – every outgoing
  entity-data packet is corrected inline, so even genuine vanilla flag
  updates (sprint, sneak, …) carry the fake glow without flicker.
- `GlowCommands`: `/glow` registration via `RegisterCommandsEvent`.
- `GlowEventHandler`: pipeline attach/detach on login/logout, re-push on
  login/respawn/dimension change/tracking start, twice-per-second tick
  (expiry + repair fallback).
- No mixins, no custom packets, no client dependency, no persistence.

## License

Copyright (c) 2026 QuantumJAKER.
Licensed under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)
(see `LICENSE`). In short: credit and link this mod whenever you share it or
an adaptation, don't earn money with it, and share adaptations under the same
license. The Forge MDK license texts live under `legal/` and are unaffected.

## AI disclosure

This mod was developed fully by AI.
All code was reviewed and verified by builds and a dedicated-server smoke
test; in-game verification with real players is still pending.
