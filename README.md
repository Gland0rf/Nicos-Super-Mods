# Nico's Super Mods (NSM)

A [Fabric](https://fabricmc.net/) mod for **Minecraft** focused on quality-of-life tools for **Hypixel SkyBlock**.

The goal of NSM is to fill niche gaps in Hypixel SkyBlock by providing high-quality, lightweight quality-of-life features that aren't available elsewhere.

> [IMPORTANT]
> NSM is an unofficial community project. It is not affiliated with or endorsed by Mojang, Microsoft, Hypixel, or the Hypixel SkyBlock Wiki. Use modifications at your own risk and follow the rules of every server you join.

## Features

### Dungeon tools
- **Room Stacking Detector**
- **Room Secret Timer**
- **Lag Monitor**

### Hypixel Wiki browser
> THIS FEATURE IS CURRENTLY UNAVAILABLE!

NSM contains an in-game browser for the Hypixel SkyBlock Wiki, so you don't have to open up your external browser every single time.

Useful controls:

| Control | Action |
|---|---|
 | `Ctrl + Right Click` | Open the wiki of the selected item
| `Ctrl + L` / `Ctrl + K` | Focus Wiki search |
| `Ctrl + T` | Open a new tab |
| `Ctrl + W` | Close the current tab |
| `Ctrl + Tab` | Next tab |
| `Ctrl + Shift + Tab` | Previous tab |
| `Ctrl + F` | Find on page |
| `Enter` / `Shift + Enter` | Next / previous find result |
| `Ctrl + D` | Toggle bookmark |
| `Ctrl + R` | Reload |
| `Alt + Left` / `Alt + Right` | Back / forward |
| `Ctrl + Click` / middle-click | Open an internal link in a new tab |
| Right-click | Open the link context menu |

### Minion tools

NSM includes minion output and ROI tools backed by the public Hypixel Skyblock Bazaar API.

### Central HUD editor

HUD elements share one central layout system.

Open it with:

```text
/nsm gui
```

HUD positions and scales are saved automatically.

## Requirements

- **Mod loader:** Fabric Loader
- **Required mods:** Fabric API, Fabric Language Kotlin, Odin, ModMenu

## Configuration

NSM uses MoulConfig for its main user-facing settings.

### Generated data

Depending on which features are used, NSM may create files under the Minecraft `config` directory.

The config location is:

```text
%APPDATA%/.minecraft/config/nicos_super_mods
```

## Acknowledgements

Some ideas and implementation details were inspired by existing Hypixel SkyBlock mods and the work of their developers.

In particular, thank you to the developer of odin for developing his mod and putting it under an open license. I could NOT imagine myself writing allat from scratch.
https://github.com/odtheking/Odin

## Network access

NSM is client-side, but some features communicate over the network:

- The Wiki browser requests public Wiki and MediaWiki data.
- Bazaar tools request public Hypixel/Bazaar data through the project's API service.
- Remote Wiki images are downloaded asynchronously.
- The lag monitor sends lightweight ping requests over the active Minecraft connection while monitoring a run.

## Known limitations

- The lag monitor reports estimates rather than authoritative server metrics.
- Hypixel UI and packet behavior can change without notice.
- Wiki markup and public APIs can change and may temporarily break parsing.
- Bazaar values depend on the availability and freshness of remote data.
- Features that depend on dungeon room detection may require updates when Hypixel changes map, scoreboard, chat, or packet formats.
- Clipboard copying is local to the client and may be unavailable on restricted desktop environments.
