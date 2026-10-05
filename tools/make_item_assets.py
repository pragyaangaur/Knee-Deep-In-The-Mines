"""Extracts Doom pickup sprites from the WAD as Minecraft item icons and writes their item models.

Run from the repository root:  python3 tools/make_item_assets.py mod/src/main/resources/doomcraft/wads/doom1.wad
Each sprite is centred on a square canvas so item/generated does not stretch it. In first
person the item model is empty, because the Doom weapon sprite is drawn on the HUD instead.
"""
import json
import os
import struct
import sys

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "mod", "src", "main", "resources", "assets", "doomcraft")

# item id -> sprite lumps to try in order, and whether it is a weapon (hidden in first person)
ITEMS = {
    "doom_chainsaw": (["CSAWA0"], True),
    "doom_pistol": (["PISTA0", "PISGA0"], True),
    "doom_shotgun": (["SHOTA0"], True),
    "doom_chaingun": (["MGUNA0"], True),
    "doom_rocket_launcher": (["LAUNA0"], True),
    "doom_plasma_rifle": (["PLASA0", "LAUNA0"], True),
    "doom_bullets": (["CLIPA0"], False),
    "doom_shells": (["SHELA0"], False),
    "doom_rockets": (["ROCKA0"], False),
    "doom_cells": (["CELLA0", "CLIPA0"], False),
}

NAMES = {
    "doom_chainsaw": "Chainsaw",
    "doom_pistol": "Pistol",
    "doom_shotgun": "Shotgun",
    "doom_chaingun": "Chaingun",
    "doom_rocket_launcher": "Rocket Launcher",
    "doom_plasma_rifle": "Plasma Rifle",
    "doom_bullets": "Bullets",
    "doom_shells": "Shotgun Shells",
    "doom_rockets": "Rockets",
    "doom_cells": "Energy Cells",
}


def read_wad(path):
    data = open(path, "rb").read()
    count, offset = struct.unpack_from("<ii", data, 4)
    lumps = {}
    for i in range(count):
        pos, size = struct.unpack_from("<ii", data, offset + i * 16)
        name = data[offset + i * 16 + 8: offset + i * 16 + 16].split(b"\0")[0].decode()
        lumps[name] = data[pos: pos + size]
    return lumps


def picture(lump, palette):
    w, h, _, _ = struct.unpack_from("<HHhh", lump, 0)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    for x in range(w):
        (p,) = struct.unpack_from("<I", lump, 8 + x * 4)
        while lump[p] != 0xFF:
            top, length = lump[p], lump[p + 1]
            for i in range(length):
                c = lump[p + 3 + i]
                if top + i < h:
                    px[x, top + i] = palette[c] + (255,)
            p += length + 4
    return img


def main():
    lumps = read_wad(sys.argv[1])
    pal = lumps["PLAYPAL"]
    palette = [tuple(pal[i * 3: i * 3 + 3]) for i in range(256)]
    os.makedirs(os.path.join(ROOT, "textures", "item"), exist_ok=True)
    os.makedirs(os.path.join(ROOT, "models", "item"), exist_ok=True)
    os.makedirs(os.path.join(ROOT, "items"), exist_ok=True)

    for item, (candidates, weapon) in ITEMS.items():
        lump = next(c for c in candidates if c in lumps)
        img = picture(lumps[lump], palette)
        side = max(img.width, img.height)
        canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        canvas.paste(img, ((side - img.width) // 2, (side - img.height) // 2))
        # Scale up so Minecraft samples it crisply at any GUI size.
        size = 64 if side <= 64 else 128
        canvas.resize((size, size), Image.NEAREST).save(os.path.join(ROOT, "textures", "item", item + ".png"))

        with open(os.path.join(ROOT, "models", "item", item + ".json"), "w") as f:
            json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "doomcraft:item/" + item}}, f, indent=2)

        model = {"type": "minecraft:model", "model": "doomcraft:item/" + item}
        if weapon:
            model = {
                "type": "minecraft:select",
                "property": "minecraft:display_context",
                "cases": [{"when": ["firstperson_righthand", "firstperson_lefthand"], "model": {"type": "minecraft:empty"}}],
                "fallback": model,
            }
        with open(os.path.join(ROOT, "items", item + ".json"), "w") as f:
            json.dump({"model": model}, f, indent=2)
        print(item, "from", lump)

    lang_path = os.path.join(ROOT, "lang", "en_us.json")
    lang = json.load(open(lang_path))
    for item, name in NAMES.items():
        lang["item.doomcraft." + item] = name
    with open(lang_path, "w") as f:
        json.dump(lang, f, indent=2)


if __name__ == "__main__":
    main()
