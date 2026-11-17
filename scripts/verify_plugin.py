#!/usr/bin/env python3
"""Fail CI when the *packaged* plugin would not register Runify's commands.

Compilation alone does not parse plugin.yml, which is how the broken build
previously passed CI despite Paper refusing to load the plugin.
"""
import sys
import zipfile
from pathlib import Path

import yaml


def main() -> None:
    jar = Path(sys.argv[1] if len(sys.argv) > 1 else "target/QualityArmory.jar")
    with zipfile.ZipFile(jar) as packaged:
        if packaged.testzip() is not None:
            raise ValueError("Packaged JAR is corrupt")
        descriptor = yaml.safe_load(packaged.read("plugin.yml"))
        if not isinstance(descriptor, dict):
            raise ValueError("plugin.yml must be a YAML mapping")
        if descriptor.get("api-version") != "1.21.11":
            raise ValueError("Wrong Minecraft API target in packaged plugin.yml")
        if descriptor.get("name") != "QualityArmory":
            raise ValueError("Plugin name changed; existing integrations may break")
        class_name = descriptor["main"].replace(".", "/") + ".class"
        if class_name not in packaged.namelist():
            raise ValueError(f"Plugin main class missing from JAR: {class_name}")
        commands = descriptor.get("commands", {})
        if not isinstance(commands, dict) or "QualityArmory" not in commands:
            raise ValueError("Missing QualityArmory command registration")
        aliases = commands["QualityArmory"].get("aliases", [])
        if not {"qa", "cua", "runify"}.issubset(set(aliases)):
            raise ValueError(f"Required command aliases missing: {aliases}")
        permissions = descriptor.get("permissions", {})
        if not isinstance(permissions, dict):
            raise ValueError("Permissions must be a YAML mapping")
        required = {"qualityarmory.admin", "qualityarmory.civ",
                    "qualityarmory.tank", "qualityarmory.cannon"}
        if not required.issubset(set(permissions)):
            raise ValueError(f"Missing command permissions: {required - set(permissions)}")
        for name, settings in permissions.items():
            if not isinstance(settings, dict):
                raise ValueError(f"Invalid permission mapping: {name} -> {settings}")
            children = settings.get("children", {})
            if not isinstance(children, dict):
                raise ValueError(f"{name}: children must be a mapping")
            for subcommand, allowed in children.items():
                if not isinstance(allowed, bool):
                    raise ValueError(f"{name}: {subcommand} permission must be true/false")
        required_classes = [
            "me/zombie_striker/qg/structures/TerritoryCivilizations.class",
            "me/zombie_striker/qg/vehicles/SiegeTankManager.class",
            "me/zombie_striker/qg/structures/SiegeCannon.class",
            "me/zombie_striker/qg/structures/VexbotVehicleController.class",
        ]
        missing = [value for value in required_classes if value not in packaged.namelist()]
        if missing:
            raise ValueError(f"Runify features are missing from packaged JAR: {missing}")
    print(f"VERIFIED {jar}: valid Paper 1.21.11 descriptor, command aliases, permissions and classes")


if __name__ == "__main__":
    main()
