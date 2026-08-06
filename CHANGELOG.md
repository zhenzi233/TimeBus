# Changelog

## v1.0.6

Mekanism CE acceleration (added after the code-review fixes):

- Time Bus can now accelerate Mekanism CE (1.12 branch, CurseForge 399904)
  machines by advancing their public onUpdate() directly - processing logic
  only, without update()'s GUI-sync packets, component ticking or ticker++
  side effects
- Energy is drawn from the machine's own Mekanism grid (no cross-mod energy
  conversion); machines simply skip ticks when power runs out
- New config: mekAccelerationEnabled (default true)
- Soft dependency: mekanism-ce added via CurseMaven as modRuntimeOnly; all
  access is reflective and degrades gracefully when Mekanism is absent
- Mekanism CE requires CodeChickenLib: the old CCL (3.2.3.358) crashes with
  an NPE on non-FG2 dev environments, so CCL CRE 3.3.5 is pulled from
  Cleanroom's official maven (3.3.8+ requires cleanroom >= 0.6.6-alpha,
  incompatible with the current 0.5.17 toolchain; 3.3.5 only needs 0.3.13+)
- dev toolchain: fixed a Groovy quirk in runClient extra JVM args handling
  (`split { "\\s+" }` coerced the result into a char list and corrupted the
  javaagent command line)
- docs: DEVELOPER.md §2.5 documents the reflection assumptions and version
  lock; README (EN/中文) updated

Code-review fixes (14-item review of v1.0.5):

- Time Bus: pulse mode no longer truncates large acceleration batches - the
  device stays awake until the whole batch finishes, then sleeps again, so a
  single redstone pulse completes a fully-upgraded run instead of ~1%
- Time Bus: fluid billing is now aligned with what was actually drained
  (partial extraction carries the remainder over instead of silently
  over-billing)
- Time Bus: unloaded chunks in the acceleration range are skipped cheaply
  instead of forcing synchronous disk loads on chunk borders
- Time Bus: per-tick config string parsing removed - speedMultipliers,
  capacityWidths and wandSpeedMultipliers are parsed once and cached
  (invalidated on config change); kept as strings so existing cfg files
  migrate cleanly
- Time Bus: fluid and storage-channel lookups in fluid mode are cached and
  re-resolved only when the configured fluid name changes
- Time Generator: an empty input slot is polled every 10 ticks instead of
  every tick; TRASH mode (if forced externally) consumes the input instead of
  leaving it stuck in the slot
- Acceleration: AE2 machine classification unified into AccelerateHelper's
  TileKind, so the bus scheduling gate and the update dispatcher can never
  drift apart
- Mixins: removed the dead TimeBusMixinConfigPlugin and leftover TEMP DIAG
  comments
- Build: removed hardcoded local JDK paths from gradle.properties (JAVA_HOME
  for Gradle 17, JDK25 env var for the compile toolchain); disabled the unused
  JUnit test configuration

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