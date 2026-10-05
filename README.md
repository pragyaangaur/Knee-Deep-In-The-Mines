# DoomCraft

DoomCraft is a Fabric mod for Minecraft 26.3 that merges Doom into Minecraft. Typing `/doom` takes you into a dimension where Doom's maps are rebuilt from the WAD, and you play them as yourself, with your Minecraft hearts, hotbar, inventory and physics. Doom's monsters are Minecraft mobs drawn with Doom's sprites, and Doom's weapons are Minecraft items. The mod also adds an arcade cabinet that runs the original Doom engine on its screen.

## Playing

Install Fabric Loader for 26.3 and Fabric API, put `mod/build/libs/doomcraft-1.0.0.jar` in the `mods` folder and start the `fabric-loader-26.3` profile.

In any world, `/doom` goes to E1M1, `/doom e1m4` goes to a chosen map, `/doom leave` goes home and `/doom reset e1m1` puts a map back to how it started. `/doom spawn imp` puts a monster in front of you. The command needs no cheats, so it works in survival. Sneaking and right-clicking the Doom Arcade block also takes you to E1M1, while right-clicking it normally sits you down to play the original game on its screen.

Inside Doom, right-click a wall to use it, which opens doors and presses switches. Left-click fires a Doom weapon. You arrive with a pistol and 50 bullets, and weapons, ammo, health, armor, powerups and keycards are picked up by walking over them. The exit switch shows Doom's tally of kills, items, secrets and time, then loads the next map. Dying restarts the map you were on.

The shareware `DOOM1.WAD` with episode 1 ships inside the mod. To play other episodes, put your own `doom.wad` or `doom2.wad` in `config/doomcraft/wads/`.

## How the two games are joined

Doom levels are read straight from the WAD. The BSP tree gives the exact floor polygon of every subsector, and the client draws Doom's walls, floors and ceilings with Doom's textures, pegging rules, sector lighting, light effects, animated flats and scrolling walls. Areas with a sky ceiling show the Minecraft sky.

The server fills each map with invisible collision blocks, so Minecraft's movement, jumping and pathfinding work unchanged. One block is 32 Doom units, which makes Steve the same height as the Doom marine. Floors are stepped in eighths of a block. A column that touches a door takes that door's height, so a closed door seals its passage even though doors are thinner than a block.

Line specials run on the server at Doom's speeds. Doors, keycard doors, lifts, floors, ceilings, stairs, donuts, light changes, teleporters, exits, secret exits, damaging floors and secret sectors are implemented. Monsters follow Doom's rules for waking, chasing, firing, pain and infighting, and they open ordinary doors. Hitscan attacks use Doom's damage and spread. One Minecraft health point is five Doom points, so a full-health Steve takes Doom's hits like a marine on 100%.

Some of Minecraft's rules give way to Doom's. Inside the Doom dimension hunger is paused and health does not regenerate on its own, so health comes only from pickups, which also top up the food bar. There is no fall damage. Doom's own pickup messages, the marine's face, the ammo count and keycards are drawn next to Minecraft's HUD, and sounds and music come from the WAD.

## Building

Clone with `git clone --recursive`, because the arcade engine is built from the doomgeneric submodule. A prebuilt macOS engine is already in the mod's resources, so `make -C engine` is only needed after changing the engine. Build the mod with JDK 25 by running `./gradlew build` in `mod/`, and the jar lands in `mod/build/libs/`.

## Layout

`engine/` builds the doomgeneric engine used by the arcade cabinet as a universal macOS binary, from the upstream source in `doomgeneric/`. `mod/` is the Fabric mod, with common code in `src/main` and rendering, sound and HUD code in `src/client`. `tools/make_item_assets.py` extracts the item icons from the WAD.

## Tests

`./gradlew runGameTest` runs headless server tests. They build E1M1, check its monsters, pickups and start position, and then open and close a door. They also run E1M2's lift, keycard door and donut, E1M3's lights going out, and a barrel explosion. `./gradlew runClientGameTest` opens a real game window and plays through E1M1 with real inputs. It enters through the arcade, checks hunger, opens the first door with a right-click and walks through it, fires the shotgun, watches an imp throw a fireball, checks falling, takes a pickup, presses the exit switch into E1M2, dies and respawns there, and leaves. Both pass on 5 October 2026.

## Limitations

The arcade cabinet's engine is built for macOS only. The Doom world itself needs no native code and should run anywhere. The shareware WAD has no sprites for the cacodemon, lost soul, plasma rifle or BFG, so those appear only with a full IWAD. Crushing ceilings and Doom II's specials are not implemented, and nor are monster-only teleporters. Collision is voxelised at one block across, so walls can sit up to half a block away from where they are drawn, and very thin features may not block. Monster pathfinding is Minecraft's, which can get stuck where Doom's would not. Only E1M1 to E1M3 have been exercised by tests. The other episode 1 maps load and render but have not been played through.
