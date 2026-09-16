# Thunderbolt Core

[简体中文](README_zh_CN.md)

Thunderbolt Core is the shared AE2 optimization and infrastructure layer for
AE2 Lightning Tech. It can also be installed as a standalone AE2 autocrafting
accelerator.

This is the **Minecraft NeoForge 1.21.1** branch. For Minecraft 1.20.1 and
Forge, see the
[`1.20.1`](https://github.com/ae2lt/Thunderbolt-Core/tree/1.20.1) branch.

## Requirements

- Minecraft `1.21.1`
- NeoForge `21.1.x` (development baseline: `21.1.219`)
- Java `21`
- Applied Energistics 2 `19.2.17`–`19.2.x`

Install Thunderbolt Core and AE2 in the `mods` directory on both the client and
server.

## Features

- faster AE2 autocrafting planning with ordered planner selection and native
  AE2 fallback
- batch dispatch, closed-loop crafting, time-wheel scheduling, and overloaded
  pattern support
- extension APIs for crafting providers, high-capacity channels, indexed
  storage cells, and eject endpoints
- max-flow channel allocation for compatible high-capacity networks
- optional compatibility hooks for Advanced AE, NeoECO, AE2 Crafting Tree,
  and ExtendedAE Plus; hooks load only when the corresponding mod is present

## Configuration

Common options are written to `config/thunderbolt-common.toml`:

- `planning.enableCpSatPlanner`: enables the experimental OR-Tools CP-SAT
  planner (default: `false`). When enabled, Thunderbolt downloads and verifies
  the matching native runtime at startup. If loading fails, the other planners
  remain available.
- `channel.mode`: controls max-flow channel allocation (default: `MOD`). `MOD`
  enables it when a loaded integration requests it, `DEVICE` also enables it
  for an opted-in device, and `ON` enables it whenever a controller is present.

Advanced planner diagnostics and safety limits can be set as JVM system
properties:

- `-Dthunderbolt.planningWarnMs=<ms>`: slow-planning warning delay (default:
  `2000`; legacy alias: `thunderbolt.watchdogMs`)
- `-Dthunderbolt.planningTimeoutMs=<ms>`: cooperative exit deadline (default:
  `3000`)
- `-Dthunderbolt.planningInterruptGraceMs=<ms>`: grace period before interrupt
  (default: `2000`)
- `-Dthunderbolt.planningStopGraceMs=<ms>`: total post-deadline grace before
  isolation (default: `5000`)
- `-Dthunderbolt.maxCraftSearchWork=<count>`: planner search-work budget
- `-Dthunderbolt.maxCraftDepth=<count>`: planner depth limit

## Development

Build the distributable JAR:

```powershell
.\gradlew.bat build
```

Publish it to the local Maven repository:

```powershell
.\gradlew.bat publishToMavenLocal
```

- Version: `2.0.0-beta.2`
- Maven coordinate: `com.moakiee.thunderbolt:thunderbolt:2.0.0-beta.2`
- Distributable JAR: `build/libs/thunderbolt-2.0.0-beta.2.jar`

Issues: [GitHub Issues](https://github.com/ae2lt/Thunderbolt-Core/issues) ·
License: [GNU LGPL 3.0](LICENSE)
