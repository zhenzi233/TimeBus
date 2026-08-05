# Changelog

## v1.0.5

Modular Machinery acceleration, wand fixes and dev-env improvements:

- Time Wand: emptying into the Time Fluid Generator no longer destroys the fluid (the fill is simulated first; output-only tank rejections are a no-op)
- Time Wand: cell size is now configurable via wandBytes (default 512, 1 byte = 1000 mB)
- Modular Machinery (CE) acceleration: restricted-tick machines are sped up by compressing the recipe duration in MM's own modifier system instead of calling update() (which their anti-acceleration design blocks); the multiplier follows the bus's speed cards (0 = 2x, 4 = 32x), opt-in via mmAccelerationEnabled, per-batch material/energy cost unchanged
- Dev environment: CraftTweaker FMLAT entries merged into timebus_at.cfg so CraftTweaker loads under Cleanroom dev + Java 25
- docs: DEVELOPER.md updated (MM acceleration, config table, dev dependencies)

## v1.0.4

Code-review fixes and UX improvements:

- Inscriber mixin hardening: consume step guards against an uncomputed batch (no item duplication), and scaled output is clamped to the item stack limit (no item loss)
- Acceleration: random-tick acceleration now calls Block.randomTick, so grass spread, ice melt, snow, and torch burnout are accelerated too
- Time Wand: no more fake particle feedback - the client prechecks resources, the server reports exactly what is missing (energy / fluid / bus idle), and batch transfer only charges when the bus actually moves items
- Time Bus: idle buses back off to a slower tick rate instead of scanning every tick
- lang files: fixed tracked file case (en_us / zh_cn), added new message keys
- docs: README acceleration semantics table, per-bus budget wording, 0-card 2x note; MODRINTH description

Requires: Minecraft 1.12.2 + CleanroomLoader + AE2 UEL