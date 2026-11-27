"""Create a fresh dedicated-server fixture for the candidate dimension experiment."""

import argparse
import json
from pathlib import Path
import re
import shutil
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
NAMESPACE = "bsp_candidate_dimensions"
OVERWORLD_NOISE = "data/minecraft/worldgen/noise_settings/overworld.json"
FLIGHT_PROFILE = {
    "sea_level": 0,
    "radius": 1000,
    "surface_gravity": 1,
    "sea_level_density": 0,
    "atmosphere_top": 0,
    "scale_height": 1,
}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def flat_settings(block, biome):
    layers = [] if block is None else [{"height": 1, "block": block}]
    return {"biome": biome, "layers": layers, "structure_overrides": []}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", help="Name of a new run directory")
    parser.add_argument("count", type=int, choices=(0, 8, 16))
    parser.add_argument("--terrain", action="store_true", help="Use stone in candidate 01 and custom noise in candidate 08")
    parser.add_argument("--sable", type=Path, metavar="JAR", help="Path to the optional Sable 2.0.6 NeoForge jar")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", args.name):
        parser.error("The run name must use letters, numbers, hyphens or underscores")
    if args.terrain and args.count != 8:
        parser.error("The terrain fixture uses eight candidates")
    if args.sable and not args.sable.is_file():
        parser.error(f"Sable jar not found: {args.sable}")

    noise = None
    if args.terrain:
        artifacts = ROOT / "build/moddev/artifacts"
        resources = next(artifacts.glob("*-client-extra-aka-minecraft-resources.jar"), None)
        if resources is None:
            parser.error("Run Gradle's createMinecraftArtifacts task before preparing the terrain fixture")
        with ZipFile(resources) as jar:
            noise = json.loads(jar.read(OVERWORLD_NOISE))

    run = ROOT / "runs" / "candidateDimensions" / args.name
    run.mkdir(parents=True)
    if args.sable:
        mods = run / "mods"
        mods.mkdir()
        shutil.copy2(args.sable, mods / args.sable.name)

    flat_overworld = json.dumps(flat_settings("minecraft:bedrock", "minecraft:plains"), separators=(",", ":"))
    (run / "server.properties").write_text(
        "level-name=world\n"
        "level-seed=67\n"
        "level-type=minecraft:flat\n"
        f"generator-settings={flat_overworld}\n"
        "generate-structures=false\n"
        "server-ip=127.0.0.1\n"
        "server-port=25667\n"
        "spawn-protection=0\n"
        "view-distance=3\n"
        "simulation-distance=3\n"
        "initial-enabled-packs=vanilla,file/candidate-dimensions\n",
        encoding="utf-8",
    )
    pack = run / "world" / "datapacks" / "candidate-dimensions"
    data = pack / "data" / NAMESPACE
    write_json(pack / "pack.mcmeta", {"pack": {"pack_format": 48, "description": "Candidate dimensions experiment"}})
    for number in range(1, args.count + 1):
        block = "minecraft:stone" if args.terrain and number == 1 else None
        generator = {"type": "minecraft:flat", "settings": flat_settings(block, "minecraft:the_void")}
        if args.terrain and number == 8:
            generator = {
                "type": "minecraft:noise",
                "biome_source": {"type": "minecraft:fixed", "biome": "minecraft:plains"},
                "settings": f"{NAMESPACE}:persisted",
            }
            write_json(data / "worldgen/noise_settings/persisted.json", noise)
        key = f"candidate_{number:02}.json"
        write_json(data / "dimension" / key, {"type": "minecraft:overworld", "generator": generator})
        write_json(data / "ship_flight" / key, FLIGHT_PROFILE)
    print(run)


if __name__ == "__main__":
    main()
