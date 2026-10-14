#!/usr/bin/env python3
"""Generate eight original cuboid hats using only the Python standard library.

Catalog coordinates are model pixels, Y up, head platform at Y=0, front at -Z.
Blockbench/Java exports add [8, 8, 8]; GLB keeps the local origin and uses 1/16 m.
The preview and the mod are deliberately outside this generator's scope.
"""

from __future__ import annotations

import base64
import json
import math
from pathlib import Path
import struct
import uuid
import zipfile
import zlib


ROOT = Path(__file__).resolve().parent
FACES = ("north", "east", "south", "west", "up", "down")
OFFSET = (8, 8, 8)
NAMESPACE = uuid.UUID("d2a3b971-5be2-4ccb-bd54-7811b3b4e7c4")


def cube(name: str, lower: tuple, upper: tuple, color: str) -> dict:
    return {"name": name, "from": list(lower), "to": list(upper), "color": color}


def designs() -> list[dict]:
    """Hand-authored silhouettes; no downloaded or copied models."""
    hats = []

    gold, dark_gold, light_gold = "#EFC04F", "#C88B2D", "#FFE28A"
    crown = [
        cube("gold_base", (-1.45, 0, -2.25), (1.45, .35, 2.25), dark_gold),
        cube("front_band", (-1.45, .35, -2.25), (1.45, 1.35, -2.0), gold),
        cube("back_band", (-1.45, .35, 2.0), (1.45, 1.35, 2.25), gold),
        cube("left_band", (-1.45, .35, -2.0), (-1.15, 1.35, 2.0), gold),
        cube("right_band", (1.15, .35, -2.0), (1.45, 1.35, 2.0), gold),
    ]
    for side, z in (("front", -2.25), ("back", 2.0)):
        for i, x in enumerate((-1.3, -.35, .75)):
            height = 2.6 if i == 1 else 2.15
            crown.append(cube(f"{side}_point_{i + 1}", (x, 1.35, z), (x + .6, height, z + .25), light_gold))
    for side, x in (("left", -1.45), ("right", 1.15)):
        for i, z in enumerate((-1.15, .65)):
            crown.append(cube(f"{side}_point_{i + 1}", (x, 1.35, z), (x + .3, 2.15, z + .55), light_gold))
    crown += [
        cube("blue_front_jewel", (-.35, .55, -2.4), (.35, 1.1, -2.24), "#56BEE8"),
        cube("ruby_left_jewel", (-1.6, .55, -.35), (-1.44, 1.05, .35), "#EB5B77"),
        cube("jade_right_jewel", (1.44, .55, -.35), (1.6, 1.05, .35), "#60D3AD"),
        cube("front_peak", (-.17, 2.6, -2.25), (.07, 2.95, -2.0), gold),
        cube("front_peak_sapphire", (-.23, 2.95, -2.3), (.13, 3.25, -1.95), "#56BEE8"),
    ]
    hats.append(hat("royal_crown", "Корона солнечного короля", "Золотой венец с десятью зубцами и тремя цветными камнями.", crown))

    ink, silk, light_ink, ribbon = "#22293B", "#303A52", "#45536C", "#A94761"
    top = [
        cube("brim_center", (-2.5, 0, -2.6), (2.5, .3, 2.6), ink),
        cube("brim_front", (-1.8, 0, -3), (1.8, .3, -2.6), ink),
        cube("brim_back", (-1.8, 0, 2.6), (1.8, .3, 3), ink),
        cube("lower_crown", (-1.65, .3, -2.3), (1.65, .75, 2.3), silk),
        cube("velvet_ribbon", (-1.68, .75, -2.33), (1.68, 1.35, 2.33), ribbon),
        cube("tall_crown", (-1.6, 1.35, -2.25), (1.6, 4.2, 2.25), silk),
        cube("polished_top", (-1.7, 4.2, -2.35), (1.7, 4.5, 2.35), light_ink),
        cube("silk_glint", (-1.35, 1.6, -2.28), (-1.15, 3.85, -2.24), light_ink),
        cube("buckle_left", (-.5, .85, -2.43), (-.35, 1.25, -2.32), gold),
        cube("buckle_right", (.35, .85, -2.43), (.5, 1.25, -2.32), gold),
        cube("buckle_top", (-.35, 1.1, -2.43), (.35, 1.25, -2.32), light_gold),
        cube("buckle_bottom", (-.35, .85, -2.43), (.35, 1.0, -2.32), gold),
    ]
    hats.append(hat("top_hat", "Полуночный цилиндр", "Высокий шелковый цилиндр с ягодной лентой и золотой пряжкой.", top))

    coal, charcoal, parchment = "#222B36", "#374352", "#F4E5C6"
    pirate = [
        cube("head_platform", (-1.2, 0, -2.15), (1.2, .35, 2.15), coal),
        cube("crown", (-1.45, .35, -2.15), (1.45, 1.95, 2.15), charcoal),
        cube("wide_front_fold", (-2.7, .25, -2.75), (2.7, 1.3, -2.35), coal),
        cube("front_peak", (-1.7, 1.3, -2.75), (1.7, 2.4, -2.35), charcoal),
        cube("front_peak_step", (-.85, 2.4, -2.75), (.85, 3.0, -2.35), charcoal),
        cube("peak_gold_trim", (-.85, 3.0, -2.79), (.85, 3.15, -2.74), gold),
        cube("left_tip", (-3.0, .35, -2.25), (-2.0, 1.5, -1.5), coal),
        cube("right_tip", (2.0, .35, -2.25), (3.0, 1.5, -1.5), coal),
        cube("left_fold", (-2.1, .25, -1.5), (-1.6, 1.25, 2.1), coal),
        cube("right_fold", (1.6, .25, -1.5), (2.1, 1.25, 2.1), coal),
        cube("rear_triangle", (-1.25, .2, 2.1), (1.25, 1.4, 2.8), coal),
        cube("front_gold_trim", (-1.7, 2.25, -2.79), (1.7, 2.4, -2.74), gold),
        cube("skull", (-.52, 1.35, -2.87), (.52, 2.05, -2.76), parchment),
        cube("skull_chin", (-.3, 1.15, -2.87), (.3, 1.4, -2.76), parchment),
        cube("left_eye", (-.38, 1.65, -2.92), (-.12, 1.87, -2.86), coal),
        cube("right_eye", (.12, 1.65, -2.92), (.38, 1.87, -2.86), coal),
        cube("nose", (-.09, 1.4, -2.93), (.09, 1.56, -2.86), coal),
        cube("left_bone_high", (-1.02, 1.4, -2.86), (-.65, 1.6, -2.76), parchment),
        cube("left_bone_low", (-.9, .95, -2.86), (-.52, 1.15, -2.76), parchment),
        cube("right_bone_high", (.65, 1.4, -2.86), (1.02, 1.6, -2.76), parchment),
        cube("right_bone_low", (.52, .95, -2.86), (.9, 1.15, -2.76), parchment),
        cube("red_knot", (1.65, .8, 1.45), (2.25, 1.35, 2.05), "#D75B67"),
        cube("red_tail_long", (1.65, .1, 1.75), (2.05, .9, 2.1), "#A43C55"),
        cube("red_tail_short", (2.05, .35, 1.75), (2.45, .95, 2.1), "#D75B67"),
    ]
    hats.append(hat("pirate_hat", "Капитан Клюв", "Треуголка с высоким передним отворотом, пиксельным черепом и алым бантом.", pirate))

    cream, gill, red, tomato, spot = "#F0DDAC", "#CAB580", "#C54450", "#E7645A", "#FFF1D5"
    mushroom = [
        cube("soft_stem_cap", (-1.2, 0, -2.15), (1.2, 1.25, 2.15), cream),
        cube("underside", (-2.4, 1.15, -2.8), (2.4, 1.4, 2.8), gill),
        cube("red_rim", (-2.65, 1.4, -3.1), (2.65, 1.95, 3.1), red),
        cube("dome_lower", (-2.2, 1.95, -2.75), (2.2, 2.65, 2.75), tomato),
        cube("dome_upper", (-1.65, 2.65, -2.1), (1.65, 3.25, 2.1), red),
        cube("dome_top", (-1.05, 3.25, -1.4), (1.05, 3.65, 1.4), tomato),
        cube("front_spot_left", (-1.75, 2.05, -2.82), (-.95, 2.5, -2.74), spot),
        cube("front_spot_right", (.65, 2.1, -2.82), (1.25, 2.58, -2.74), spot),
        cube("front_rim_spot", (-.3, 1.55, -3.18), (.45, 1.85, -3.09), cream),
        cube("right_spot", (2.19, 2.05, -.65), (2.29, 2.5, .2), spot),
        cube("left_spot", (-2.29, 2.05, .65), (-2.19, 2.5, 1.55), spot),
        cube("rear_spot", (-.4, 2.0, 2.74), (.45, 2.5, 2.85), cream),
        cube("top_spot_left", (-1.3, 3.26, -.9), (-.75, 3.33, -.25), spot),
        cube("top_spot_center", (-.25, 3.65, .15), (.5, 3.73, .85), spot),
        cube("upper_spot", (.5, 2.85, -2.18), (1.1, 3.15, -2.09), spot),
        cube("left_gill", (-2.2, 1.08, -.95), (-1.22, 1.16, -.65), cream),
        cube("right_gill", (1.22, 1.08, .6), (2.2, 1.16, .9), cream),
    ]
    hats.append(hat("mushroom_hat", "Лесной мухомор", "Кремовая подкладка, ступенчатая красная шляпка и объемные светлые пятна.", mushroom))

    navy, purple, lilac = "#34395E", "#565385", "#7773A7"
    wizard = [
        cube("brim_center", (-2.65, 0, -2.6), (2.65, .3, 2.6), navy),
        cube("brim_front", (-1.9, 0, -3.1), (1.9, .3, -2.6), navy),
        cube("brim_back", (-1.9, 0, 2.6), (1.9, .3, 3.1), navy),
        cube("gold_hatband", (-1.55, .3, -2.25), (1.55, .75, 2.25), gold),
        cube("cone_base", (-1.45, .75, -2.15), (1.45, 1.7, 2.15), purple),
        cube("cone_step_two", (-1.2, 1.7, -1.8), (1.2, 2.7, 1.8), navy),
        cube("cone_step_three", (-.8, 2.7, -1.35), (1.0, 3.65, 1.35), purple),
        cube("cone_step_four", (-.45, 3.65, -.9), (.9, 4.5, .9), lilac),
        cube("bent_tip_base", (.05, 4.5, -.55), (.9, 5.2, .55), purple),
        cube("bent_tip", (.5, 5.2, -.35), (1.25, 5.7, .35), lilac),
        cube("tip_gold_bead", (1.0, 5.55, -.25), (1.4, 5.95, .25), light_gold),
        cube("star_one_vertical", (-.85, 1.0, -2.25), (-.6, 1.55, -2.14), light_gold),
        cube("star_one_horizontal", (-1.0, 1.17, -2.26), (-.45, 1.4, -2.14), light_gold),
        cube("star_two_vertical", (.5, 1.95, -1.9), (.7, 2.5, -1.79), gold),
        cube("star_two_horizontal", (.3, 2.12, -1.91), (.9, 2.33, -1.79), gold),
        cube("moon_left", (-.65, 2.95, -1.45), (-.43, 3.45, -1.34), "#91DAD3"),
        cube("moon_top", (-.45, 3.4, -1.45), (.05, 3.58, -1.34), "#91DAD3"),
        cube("moon_bottom", (-.45, 2.8, -1.45), (.05, 3.0, -1.34), "#91DAD3"),
    ]
    hats.append(hat("wizard_hat", "Звездочет", "Высокий слегка загнутый колпак с золотыми звездами и бирюзовой луной.", wizard))

    blue, coral, mint, yellow = "#568FC6", "#E16D72", "#74C7AD", "#F5CF6B"
    propeller = [cube("cap_platform", (-1.2, 0, -2.15), (1.2, .3, 2.15), "#3D597D")]
    for name, x0, x1, z0, z1, color in (
        ("front_left", -1.4, 0, -2.15, 0, coral), ("front_right", 0, 1.4, -2.15, 0, blue),
        ("back_left", -1.4, 0, 0, 2.15, yellow), ("back_right", 0, 1.4, 0, 2.15, mint),
    ):
        propeller.append(cube(f"panel_{name}", (x0, .3, z0), (x1, 1.5, z1), color))
        propeller.append(cube(f"dome_{name}", (x0 * .78, 1.5, z0 * .78), (x1 * .78, 2.15, z1 * .78), color))
    propeller += [
        cube("visor", (-1.9, .1, -3.15), (1.9, .35, -2.05), blue),
        cube("visor_front", (-1.4, .1, -3.45), (1.4, .35, -3.15), "#3D597D"),
        cube("top_button", (-.35, 2.15, -.35), (.35, 2.45, .35), "#3D597D"),
        cube("rotor_stem", (-.14, 2.45, -.14), (.14, 3.2, .14), gold),
        cube("rotor_left", (-2.95, 3.2, -.32), (-.2, 3.43, .32), coral),
        cube("rotor_right", (.2, 3.2, -.32), (2.95, 3.43, .32), yellow),
        cube("rotor_front", (-.3, 3.23, -2.6), (.3, 3.46, -.2), mint),
        cube("rotor_back", (-.3, 3.23, .2), (.3, 3.46, 2.6), blue),
        cube("rotor_hub", (-.37, 3.18, -.37), (.37, 3.6, .37), "#FFF1D5"),
    ]
    hats.append(hat("propeller_cap", "Винт-пилот", "Четырехцветная кепка с длинным козырьком и ярким четырехлопастным пропеллером.", propeller))

    tan, leather, suede, sand = "#BA8052", "#754C39", "#CF9B67", "#E6BC80"
    cowboy = [
        cube("brim_center", (-2.5, 0, -2.8), (2.5, .3, 2.8), tan),
        cube("brim_front", (-2.0, 0, -3.2), (2.0, .3, -2.8), tan),
        cube("brim_back", (-2.0, 0, 2.8), (2.0, .3, 3.2), tan),
        cube("left_curl_lower", (-2.95, .2, -2.45), (-2.45, .65, 2.45), suede),
        cube("right_curl_lower", (2.45, .2, -2.45), (2.95, .65, 2.45), suede),
        cube("left_curl_top", (-3.1, .65, -2.0), (-2.7, 1.0, 2.0), sand),
        cube("right_curl_top", (2.7, .65, -2.0), (3.1, 1.0, 2.0), sand),
        cube("crown_base", (-1.6, .3, -2.2), (1.6, .75, 2.2), suede),
        cube("leather_band", (-1.64, .75, -2.24), (1.64, 1.15, 2.24), leather),
        cube("crown", (-1.5, 1.15, -2.1), (1.5, 2.35, 2.1), suede),
        cube("left_ridge", (-1.4, 2.35, -1.9), (-.35, 3.05, 1.9), sand),
        cube("right_ridge", (.35, 2.35, -1.9), (1.4, 3.05, 1.9), sand),
        cube("center_crease", (-.35, 2.35, -1.9), (.35, 2.55, 1.9), tan),
        cube("buckle_left", (-.45, .8, -2.36), (-.3, 1.1, -2.23), gold),
        cube("buckle_right", (.3, .8, -2.36), (.45, 1.1, -2.23), gold),
        cube("buckle_top", (-.3, .97, -2.36), (.3, 1.1, -2.23), light_gold),
        cube("buckle_bottom", (-.3, .8, -2.36), (.3, .93, -2.23), gold),
        cube("side_feather_base", (1.49, 1.25, .8), (1.65, 1.75, 1.15), "#6BABC4"),
        cube("side_feather_mid", (1.49, 1.75, .65), (1.65, 2.15, 1.05), "#91DAD3"),
        cube("side_feather_tip", (1.49, 2.15, .5), (1.65, 2.5, .85), "#6BABC4"),
    ]
    hats.append(hat("cowboy_hat", "Шериф джунглей", "Песочный стетсон с приподнятыми полями, продольной складкой и маленьким пером.", cowboy))

    steel, silver, dark_steel, glass = "#8497AA", "#BAC8D3", "#4F6378", "#75CBCD"
    ufo = [
        cube("head_platform", (-1.2, 0, -2.15), (1.2, .4, 2.15), dark_steel),
        cube("saucer_belly", (-2.25, .25, -2.55), (2.25, .55, 2.55), steel),
        cube("wide_saucer_rim", (-2.8, .55, -2.6), (2.8, 1.0, 2.6), silver),
        cube("front_saucer_rim", (-2.05, .55, -3.15), (2.05, 1.0, -2.6), silver),
        cube("back_saucer_rim", (-2.05, .55, 2.6), (2.05, 1.0, 3.15), silver),
        cube("upper_saucer", (-2.1, 1.0, -2.6), (2.1, 1.35, 2.6), steel),
        cube("cockpit_frame", (-1.55, 1.35, -2.0), (1.55, 1.55, 2.0), dark_steel),
        cube("cockpit_lower", (-1.45, 1.55, -1.9), (1.45, 2.2, 1.9), glass),
        cube("cockpit_upper", (-1.15, 2.2, -1.55), (1.15, 2.65, 1.55), "#ADE5DB"),
        cube("cockpit_top", (-.8, 2.65, -1.0), (.8, 2.95, 1.0), glass),
        cube("cockpit_highlight", (-.95, 2.32, -1.62), (-.25, 2.5, -1.54), "#ECFFF1"),
        cube("front_green_light_left", (-1.25, .65, -3.23), (-.8, .9, -3.14), "#A6DF91"),
        cube("front_green_light_right", (.8, .65, -3.23), (1.25, .9, -3.14), "#A6DF91"),
        cube("rear_green_light", (-.25, .65, 3.14), (.25, .9, 3.23), "#A6DF91"),
        cube("left_amber_light", (-2.9, .65, -.3), (-2.79, .9, .3), yellow),
        cube("right_amber_light", (2.79, .65, -.3), (2.9, .9, .3), yellow),
        cube("antenna", (-.08, 2.95, -.08), (.08, 3.7, .08), dark_steel),
        cube("antenna_beacon", (-.24, 3.7, -.24), (.24, 4.05, .24), coral),
    ]
    hats.append(hat("ufo_hat", "Посол с орбиты", "Серебристая тарелка с мятной кабиной, сигнальными огнями и маленькой антенной.", ufo))
    return hats


def hat(identifier: str, name: str, description: str, cubes: list[dict]) -> dict:
    return {"id": identifier, "name": name, "description": description, "cubes": cubes,
            "files": {"bbmodel": f"bbmodel/{identifier}.bbmodel",
                      "minecraft": f"models/item/{identifier}.json",
                      "texture": f"textures/item/{identifier}.png", "glb": f"glb/{identifier}.glb"}}


def png_palette(colors: list[str]) -> bytes:
    """A valid 16x16 RGBA PNG with stable one-pixel palette cells."""
    pixels = [tuple(bytes.fromhex(color[1:])) + (255,) for color in colors]
    pixels += [(0, 0, 0, 0)] * (256 - len(pixels))
    rows = b"".join(b"\x00" + bytes(channel for pixel in pixels[y * 16:(y + 1) * 16] for channel in pixel)
                    for y in range(16))

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def uid(identifier: str, element: str) -> str:
    return str(uuid.uuid5(NAMESPACE, f"{identifier}/{element}"))


def uv_for(color: str, palette: list[str]) -> list[int]:
    index = palette.index(color)
    return [index % 16, index // 16, index % 16 + 1, index // 16 + 1]


def offset(vector: list[float]) -> list[float]:
    return [round(value + shift, 4) for value, shift in zip(vector, OFFSET)]


def blockbench_model(model: dict, palette: list[str], png: bytes) -> dict:
    elements = []
    for i, item in enumerate(model["cubes"]):
        elements.append({"name": item["name"], "type": "cube", "uuid": uid(model["id"], item["name"]),
                         "from": offset(item["from"]), "to": offset(item["to"]), "origin": list(OFFSET),
                         "rotation": [0, 0, 0], "box_uv": False, "autouv": 0, "color": i % 8,
                         "faces": {face: {"uv": uv_for(item["color"], palette), "texture": 0} for face in FACES}})
    return {
        "meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False},
        "name": model["name"], "model_identifier": model["id"], "visible_box": [1, 1, 0],
        "resolution": {"width": 16, "height": 16}, "elements": elements,
        "outliner": [{"name": model["name"], "uuid": uid(model["id"], "hat_group"), "origin": list(OFFSET),
                      "rotation": [0, 0, 0], "children": [element["uuid"] for element in elements]}],
        "textures": [{"name": f"{model['id']}.png", "id": "0", "uuid": uid(model["id"], "palette"),
                      "folder": "item", "namespace": "viewerparrots", "width": 16, "height": 16,
                      "uv_width": 16, "uv_height": 16, "mode": "bitmap", "saved": True, "internal": True,
                      "source": "data:image/png;base64," + base64.b64encode(png).decode("ascii")}],
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [1.5, 1.5, 1.5]}},
    }


def minecraft_model(model: dict, palette: list[str]) -> dict:
    return {
        "credit": "Original Viewer Parrots hat-studio design",
        "ambientocclusion": False,
        "textures": {"0": f"viewerparrots:item/{model['id']}", "particle": "#0"},
        "elements": [{"name": item["name"], "from": offset(item["from"]), "to": offset(item["to"]),
                      "faces": {face: {"uv": uv_for(item["color"], palette), "texture": "#0"} for face in FACES}}
                     for item in model["cubes"]],
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [1.5, 1.5, 1.5]},
                    "ground": {"translation": [0, 3, 0], "scale": [.8, .8, .8]}},
    }


def cube_mesh(item: dict) -> tuple[list[tuple], list[tuple], list[int]]:
    x0, y0, z0 = (value / 16 for value in item["from"])
    x1, y1, z1 = (value / 16 for value in item["to"])
    sides = [
        (((x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0)), (0, 0, -1)),
        (((x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (x0, y0, z1)), (0, 0, 1)),
        (((x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (x0, y0, z0)), (-1, 0, 0)),
        (((x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)), (1, 0, 0)),
        (((x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)), (0, 1, 0)),
        (((x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)), (0, -1, 0)),
    ]
    positions, normals, indices = [], [], []
    for corners, normal in sides:
        start = len(positions)
        positions.extend(corners)
        normals.extend([normal] * 4)
        indices.extend(start + value for value in (0, 1, 2, 0, 2, 3))
    return positions, normals, indices


def glb_model(model: dict, palette: list[str]) -> bytes:
    binary = bytearray()
    views, accessors, meshes, nodes = [], [], [], []

    def add_accessor(values: list, shape: str, component: int, target: int, bounds: bool = False) -> int:
        while len(binary) % 4:
            binary.append(0)
        flat = [v for vector in values for v in vector] if shape == "VEC3" else values
        payload = struct.pack("<" + ("f" if component == 5126 else "H") * len(flat), *flat)
        view_index = len(views)
        views.append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(payload), "target": target})
        binary.extend(payload)
        accessor = {"bufferView": view_index, "byteOffset": 0, "componentType": component,
                    "count": len(values), "type": shape}
        if bounds:
            accessor["min"] = [min(vector[i] for vector in values) for i in range(3)]
            accessor["max"] = [max(vector[i] for vector in values) for i in range(3)]
        accessors.append(accessor)
        return len(accessors) - 1

    for item in model["cubes"]:
        positions, normals, indices = cube_mesh(item)
        p = add_accessor(positions, "VEC3", 5126, 34962, True)
        n = add_accessor(normals, "VEC3", 5126, 34962)
        idx = add_accessor(indices, "SCALAR", 5123, 34963)
        meshes.append({"name": item["name"], "primitives": [{"attributes": {"POSITION": p, "NORMAL": n},
                        "indices": idx, "material": palette.index(item["color"]), "mode": 4}]})
        nodes.append({"name": item["name"], "mesh": len(meshes) - 1,
                      "extras": {"fromPixels": item["from"], "toPixels": item["to"], "color": item["color"]}})
    document = {
        "asset": {"version": "2.0", "generator": "Viewer Parrots hat-studio / Python stdlib"},
        "scene": 0, "scenes": [{"name": model["name"], "nodes": list(range(len(nodes)))}],
        "nodes": nodes, "meshes": meshes,
        "materials": [{"name": color, "pbrMetallicRoughness": {
            "baseColorFactor": [srgb_to_linear(int(color[i:i + 2], 16) / 255) for i in (1, 3, 5)] + [1],
            "metallicFactor": 0, "roughnessFactor": .85}} for color in palette],
        "buffers": [{"byteLength": len(binary)}], "bufferViews": views, "accessors": accessors,
        "extras": {"modelPixelMeters": 1 / 16, "front": "-Z", "platformY": 0, "originalDesign": True},
    }
    encoded_json = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    encoded_json += b" " * (-len(encoded_json) % 4)
    binary.extend(b"\x00" * (-len(binary) % 4))
    total = 12 + 8 + len(encoded_json) + 8 + len(binary)
    return (struct.pack("<4sII", b"glTF", 2, total) + struct.pack("<II", len(encoded_json), 0x4E4F534A)
            + encoded_json + struct.pack("<II", len(binary), 0x004E4942) + binary)


def srgb_to_linear(value: float) -> float:
    # glTF baseColorFactor is linear; PNG/catalog hex colors are sRGB.
    return value / 12.92 if value <= .04045 else ((value + .055) / 1.055) ** 2.4


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def validate(models: list[dict]) -> dict:
    """Inspect actual generated files, including PNG CRCs and GLB buffer bounds."""
    summaries = []
    for model in models:
        assert 8 <= len(model["cubes"]) <= 35, model["id"]
        assert 3 <= max(item["to"][1] for item in model["cubes"]) <= 6
        names = set()
        for item in model["cubes"]:
            assert item["name"] not in names
            names.add(item["name"])
            assert len(item["from"]) == len(item["to"]) == 3
            assert all(math.isfinite(v) for v in item["from"] + item["to"])
            assert all(a < b for a, b in zip(item["from"], item["to"]))
            assert item["from"][1] >= 0
            assert all(-16 <= v <= 32 for v in offset(item["from"]) + offset(item["to"]))
        bb = json.loads((ROOT / model["files"]["bbmodel"]).read_text(encoding="utf-8"))
        mc = json.loads((ROOT / model["files"]["minecraft"]).read_text(encoding="utf-8"))
        assert bb["meta"]["model_format"] == "java_block"
        assert len(bb["elements"]) == len(mc["elements"]) == len(model["cubes"])
        assert len({element["uuid"] for element in bb["elements"]}) == len(model["cubes"])
        for item, bb_cube, mc_cube in zip(model["cubes"], bb["elements"], mc["elements"]):
            assert bb_cube["from"] == mc_cube["from"] == offset(item["from"])
            assert bb_cube["to"] == mc_cube["to"] == offset(item["to"])
            assert set(bb_cube["faces"]) == set(mc_cube["faces"]) == set(FACES)
            for face in FACES:
                assert all(0 <= coordinate <= 16 for coordinate in bb_cube["faces"][face]["uv"])
        png = (ROOT / model["files"]["texture"]).read_bytes()
        assert png[:8] == b"\x89PNG\r\n\x1a\n"
        assert base64.b64decode(bb["textures"][0]["source"].split(",", 1)[1]) == png
        position, compressed = 8, b""
        while position < len(png):
            size = struct.unpack_from(">I", png, position)[0]
            kind = png[position + 4:position + 8]
            data = png[position + 8:position + 8 + size]
            crc = struct.unpack_from(">I", png, position + 8 + size)[0]
            assert zlib.crc32(kind + data) & 0xffffffff == crc
            if kind == b"IHDR":
                assert struct.unpack(">IIBBBBB", data) == (16, 16, 8, 6, 0, 0, 0)
            if kind == b"IDAT":
                compressed += data
            position += size + 12
        rows = zlib.decompress(compressed)
        assert len(rows) == 16 * 65
        for color_index, color in enumerate(dict.fromkeys(item["color"] for item in model["cubes"])):
            row, col = divmod(color_index, 16)
            pixel_offset = row * 65 + 1 + col * 4
            assert rows[pixel_offset:pixel_offset + 4] == bytes.fromhex(color[1:]) + b"\xff"
        glb = (ROOT / model["files"]["glb"]).read_bytes()
        assert struct.unpack_from("<4sII", glb) == (b"glTF", 2, len(glb))
        json_size, json_type = struct.unpack_from("<II", glb, 12)
        assert json_type == 0x4E4F534A and json_size % 4 == 0
        gltf = json.loads(glb[20:20 + json_size])
        bin_size, bin_type = struct.unpack_from("<II", glb, 20 + json_size)
        assert bin_type == 0x004E4942 and bin_size % 4 == 0
        assert 28 + json_size + bin_size == len(glb)
        assert len(gltf["nodes"]) == len(model["cubes"])
        for view in gltf["bufferViews"]:
            assert view["byteOffset"] % 4 == 0
            assert view["byteOffset"] + view["byteLength"] <= bin_size
        for item, mesh in zip(model["cubes"], gltf["meshes"]):
            accessor = gltf["accessors"][mesh["primitives"][0]["attributes"]["POSITION"]]
            assert accessor["min"] == [v / 16 for v in item["from"]]
            assert accessor["max"] == [v / 16 for v in item["to"]]
        summaries.append({"id": model["id"], "cuboids": len(model["cubes"]),
                          "heightPixels": max(item["to"][1] for item in model["cubes"]),
                          "pngBytes": len(png), "glbBytes": len(glb)})
    return {"valid": True, "hatCount": len(models), "checks": ["cuboid bounds", "Java offset", "UV palette",
            "embedded PNG", "PNG CRC/RGBA", "GLB v2 headers", "GLB buffer bounds"], "hats": summaries}


def main() -> None:
    models = designs()
    for model in models:
        palette = list(dict.fromkeys(item["color"] for item in model["cubes"]))
        png = png_palette(palette)
        texture_path = ROOT / model["files"]["texture"]
        texture_path.parent.mkdir(parents=True, exist_ok=True)
        texture_path.write_bytes(png)
        write_json(ROOT / model["files"]["bbmodel"], blockbench_model(model, palette, png))
        write_json(ROOT / model["files"]["minecraft"], minecraft_model(model, palette))
        glb_path = ROOT / model["files"]["glb"]
        glb_path.parent.mkdir(parents=True, exist_ok=True)
        glb_path.write_bytes(glb_model(model, palette))
    write_json(ROOT / "catalog.json", {"title": "Шляпы для попугаев", "hats": models})
    report = validate(models)
    write_json(ROOT / "validation.json", report)
    with zipfile.ZipFile(ROOT / "hat-models.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in (ROOT / "catalog.json", ROOT / "validation.json", Path(__file__).resolve()):
            archive.write(path, path.relative_to(ROOT).as_posix())
        for model in models:
            for relative in model["files"].values():
                archive.write(ROOT / relative, relative)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
