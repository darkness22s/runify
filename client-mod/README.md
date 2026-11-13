# QualityArmory Hit Preview (Fabric)

Client-side hit preview for Minecraft 1.20.6 / Fabric Loader 0.15.11+ (Java 21).

## Build

From this directory, run `gradle build` with Gradle 8.12 or newer. The jar is written to `build/libs/`.

## Behavior

While the crosshair is on a living entity, the vanilla reticle is replaced by a red reticle and an estimated damage label. Reticle intensity increases with the estimated damage relative to the target's current health; lethal estimates use the brightest shade and show `LETHAL`. The estimate reads the `Damage: <amount>` lore line on the held weapon, uses the vanilla armor/toughness reduction formula, includes the target's synchronized absorption, and applies QualityArmory's default 2x headshot multiplier when aiming at the upper portion of the hitbox.

This is advisory only. Servers can change damage, headshot multipliers, armor effects, bullet protection, or weapon spread, and those server-specific rules are not available to the client in this version. The preview therefore cannot guarantee the damage the server will ultimately apply. The client mod does not alter weapon hits or send targeting information to the server.
