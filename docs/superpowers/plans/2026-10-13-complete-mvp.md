> ИСТОРИЧЕСКИЙ ДОКУМЕНТ. С 14 октября 2026 года действует ТЗ 2.0: Twitch-чат и одиночная игра. Серверные и нагрузочные gates ниже больше не являются требованиями; незавершённые пункты старого плана не продолжаются автоматически.

# Viewer Parrots MVP completion plan

> **For agentic workers:** Use Superpowers development, debugging, TDD and verification workflows. The user explicitly delegated autonomous completion, installation and testing while asleep; proceed without interactive design/plan gates.

**Goal:** Deliver an installed, reproducibly built Fabric 1.21.1 mod covering the existing specification, with evidence for functional and persistence checks.

**Architecture:** Keep the current domain/storage layers and Fabric adapters. Complete personality scheduling and lifecycle contracts in independent file scopes, add game-level acceptance tests, then package and install the verified release.

**Tech Stack:** Java 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17+1.21.1, Loom 1.17.20, Gradle wrapper 9.5.1, JUnit 5.

**Spec:** `viewer_parrots_technical_spec.md`; existing accepted deviations in README: dance 200 ticks and void rescue in IMMORTAL.

## Global constraints

- Preserve viewer/incarnation/generation identity and durable PREPARE/COMMIT ordering.
- Server owns behavior, permissions, sounds and tracked dance; no client classes in common code.
- No TTS, external services, item pickup, economy or additional HERO/THIEF effects.
- Use bounded local searches and scheduling; never scan dormant registry/world every tick.
- Retain existing user worlds/configuration; test worlds and evidence are separate.
- Report actual checks; never claim unperformed visual/manual scenarios passed.

## Review focus

- World unload and dimension transfer must clear stale runtime/entity references without losing identity.
- Concurrent create/remove/respawn and delayed callbacks must not authorize duplicate or removed entities.
- Invalid reload must preserve the entire prior configuration and existing deadlines.
- Autonomous and command sounds must share limits; sit and accepted explicit commands must interrupt autonomous movement.
- Error isolation must leave explicit commands usable and freeze entities on storage failure.

## Tasks

### Task 1: Personality behavior and scheduler

Files: behavior package, ViewerParrotEntity, optional entity adapters, unit tests for deterministic behavior policy.

Interfaces: consume ServerContext config/random/registry/cooldowns/rateLimiter/sounds/loadedIndex; preserve existing runtime and command interfaces, coordinate new cancellation methods with Task 2.

- [x] Write deterministic tests for idle jitter, LOUD/NORMAL sound probability, disabled behavior, COWARD bounds and fair per-tick budgets; observe relevant failures.
- [x] Implement bounded IDLE maneuvers, hostile-local checks, independent jitter, shared sound limits and custom goals that do not compete with command navigation.
- [x] Enforce global path/teleport/danger budgets and runtime cleanup/error reset helpers.
- [x] Run targeted tests and compile; record results.

### Task 2: Configuration, lifecycle and command contracts

Files: ServerContext, FabricLifecycleAdapter, service package, ViewerParrotCommands, CommandFeedback, language resources, config tests and event tests.

Interfaces: consume existing runtime, behavior scheduler and persistence APIs; provide atomic reload and final asynchronous command feedback; no edits to Task 1 files without coordination.

- [x] Reproduce reload/disabled LAZY/lifecycle classification and event-listener mutation defects with meaningful tests.
- [x] Implement registry-aware sound validation, atomic reload with restart-only restrictions and bounded name refresh.
- [x] Complete lifecycle events/runtime cancellation and deferred action completion feedback; preserve durable transaction ordering and service permission checks.
- [x] Add useful diagnostics and help; verify reload, permissions and lifecycle behavior.

### Task 3: Game-level acceptance and fault verification

Files: build.gradle, test mod/gametest sources, scripts, CI, unit persistence tests.

Interfaces: tests exercise final services/entities/commands using a dedicated test environment and real Minecraft tick lifecycle.

- [x] Add isolated Fabric GameTest setup and meaningful entity/behavior/lifecycle tests.
- [x] Verify create, duplicate create, identity/NBT, dance, sit/follow, traits, immortality/death, recovery and stale generations in a server.
- [ ] Exercise storage replay, abrupt process termination and restart; measure bounded workload and runtime cleanup.
- [x] Run dedicated startup and production-client/integrated checks available on this machine.

### Task 4: Release, installation and independent review

Files: README, example config, verification report, release scripts, version metadata.

- [x] Review the full diff independently; fix important findings with regressions.
- [x] Run clean build, full unit suite, game acceptance suite and server startup smoke.
- [ ] Package release JAR and sources with config/install/backup/recovery instructions and factual test evidence.
- [x] Install final JAR into the existing Prism 1.21.1 Fabric instance, preserving worlds and backing up the previous mod.
- [ ] Commit project changes locally and leave the user a direct launch/install path and known validation limits.

## Execution ledger

- Baseline: `gradlew.bat test build --console=plain` passes on 2026-10-13; original HEAD d5e3936, branch main. Existing untracked `.serena/` is user/tool state and must not be committed.
- Decision: use the supplied technical specification and current checkout, create a completion branch; user delegated all routine design/execution choices. Changes are local until complete, no remote publish requested.
- 20:44–21:35 MSK: final clean build passed (328 unit tests, 19 GameTests); exact release JAR installed and exercised in the official Prism client's separate integrated QA world. Eleven process crash/storage scenarios and formal 10,000-record bootstrap passed. Nine full benchmark runs and final two-client visual receipt remain in progress; exact AC-13/AC-14 disk fixtures are being completed.
- 23:32 MSK: exact AC-13/AC-14 disk fixtures passed; two actual dedicated clients have directly reviewed dance/late-tracking/expiry/Nether images and positional sound receipts. Level-0/2 commands and ordinary restart preserve identity. Original Mojang Vanilla client was refused before PLAY and server remained READY; optional native GUI capture failed separately after refusal. Full benchmark has four of nine complete windows (three baseline, one vanilla50), all approximately 20 TPS; final source build and packaging wait for the remaining windows.
- QA driver reproduction: fixed normal Gradle launch classpath resolution for disk lifecycle overlays. Three filesystem regressions pass, including rejection of changed copied production bytes; production and protected benchmark runtime remain unchanged.
- 00:45 MSK 14 October: all nine full windows of pre-diagnostics JAR `9810CD0F…FC303` passed; maximum custom p95 tick addition0.853ms/scheduler0.030ms, storageREADY/50realAI bodies retained. Protected stand saved all dimensions and exited0. Final crosswalk identified §6/7/8/11/13 gaps; a new production wave adds bounded structured diagnostics, common API/CLI event/result handling, shutdown ingress and deathMode revalidation with real dispatcher/server regressions. Fresh verification and release of these bytes remain required.
