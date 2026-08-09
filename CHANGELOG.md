# Changelog

## v1.0.9

Code-review fixes (2026-08-09 review report):

- Fix: multiple Time Buses on the same cable no longer overwrite / wrongly
  clear each other's MM acceleration - the modifier source key now includes the
  part side; legacy side-less bus modifiers are purged automatically on upgrade
- Fix: the Time Wand no longer leaves MM controllers permanently accelerated -
  a wand click now accelerates the currently running recipe until it finishes
  via MM's semi-permanent modifiers (recipe duration x1/speed, per-tick energy
  x speed when `MM Energy Cost Follows Speed` is on); MM auto-restores normal
  speed when the recipe finishes or fails, so nothing is written permanently
  into the save, and idle machines are not charged
- Fix: injected MM modifiers are cleaned up when a single chunk unloads (they
  were previously written into the save and survived a server restart)
- Perf: MM acceleration skips the per-tick reflection sweep in steady state
  (state snapshot + the existing periodic force-refresh self-heals within one
  `MM Context Refresh Interval`)
- Perf: `TileCharger.doWork()` switched from cached reflection to a Mixin
  `@Invoker`; the Time Bus source key string is now lazily cached
- Balance: wand bus-batch size is capped at 256 (config range + runtime clamp);
  random-tick acceleration per speed unit is now configurable
  (`Random Tick Calls per Speed Unit`, default 20)
- Fix: Time Wand fluid capacity display was 8x too small (AE2EL stores
  8000 mB per byte, not 1000)
- Docs: corrected the `Power Cost per Speed Unit` comment to match the actual
  formula; updated wand byte/mB documentation; debug logs for swallowed config
  exceptions; MM state caches are cleaned up on restore

## v1.0.8

- MM acceleration: energy cost now follows the speed-up (new `MM Energy Cost Follows Speed`, default true) - per-tick energy consumption and production are multiplied by the same factor as the duration compression, so total energy per recipe stays constant while the output rate increases
- MM acceleration: fixed threads staying unaccelerated after MM recycles its pooled recipe crafting contexts (the modifier data was correct but the actual context had lost it) - the Time Bus now force re-applies modifiers every `MM Context Refresh Interval` ticks (default 20 = about 1 second, 0 disables), self-healing within one interval

## v1.0.7

- Time Fluid Generator: consumes up to 64 inputs per update (configurable via `Inputs Consumed per Update`), so Time Bus acceleration now actually speeds it up; the input slot stacks up to 64
- MM acceleration: the injected duration modifier now targets `REQUIREMENT_DURATION` (previously null), which makes acceleration actually take effect and fixes a client crash (`Could not find requirementType`) caused by serializing a modifier with an empty target
- MM acceleration: removed the reflection-caching fast path - MM recycles idle factory threads (`invalidate()` clears permanent modifiers), which could leave threads permanently unaccelerated; back to per-tick verification
- MM factory controllers: new `MM Keep Idle Threads` option (default true) - accelerated controllers skip the 200-tick idle-thread recycle so threads stay accelerated
- dev: added Mekanism-CE-Unofficial runtime dependency for compatibility testing

## v1.0.6

- MM acceleration now stacks: each Time Bus / wand injects its own per-source
  duration modifier, and MM multiplies them together (8x + 4x = 32x); removing
  a bus restores its modifier (previously it stayed on the machine and was
  even saved into the world save)
- MM acceleration also supports factory controllers (TileFactoryController)
- dev toolchain: fixed a Groovy quirk in runClient extra JVM args handling
  (`split { "\\s+" }` coerced the result into a char list and corrupted the
  javaagent command line)
- docs: DEVELOPER.md / README (EN/中文) updated

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
