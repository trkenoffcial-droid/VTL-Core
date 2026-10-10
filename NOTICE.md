# VTL Core

Copyright (c) 2026 Viet TierList. VTL Core's own changes and additions are licensed under the
Mozilla Public License 2.0 (`LICENSE`), like the TierTagger code they build on. The names and
logos are not licensed: see `TRADEMARKS.md`.

# Third-party content

## Block-Accurate: 3D Item Models

- Source: https://github.com/teyokuma/3D-Item-Models (commit 9743a75dce)
- Copyright (c) 2024 teyokuma
- License: MIT (`src/main/resources/resourcepacks/block_accurate/LICENSE.txt`)

Bundled as the built-in resource pack "3D item icons", off by default. Left
out: the README images and the 26.x overlays. Changed: the `pack.mcmeta`
description and overlay list. Details in
`src/main/resources/resourcepacks/block_accurate/SOURCE.txt`.

## Minecraft

Kit card images (`assets/vtl/textures/gui/kit/`) are renders of Minecraft's own
player and item models, made with `./gradlew runClient -Pdevshots` and
`tools/kit-art.js`. Minecraft is a trademark of Mojang AB; this mod is not an
official Minecraft product and is not approved by or associated with Mojang or
Microsoft.

## TierTagger

The mod started as a fork of TierTagger by uku and netiyiy (MPL-2.0, see
`LICENSE`).
