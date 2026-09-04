# RCDoors Wizard Rework: Session Handoff

**Branch:** `claude/new-session-f5iyu0` (continues `claude/last-commits-review-s1469s`)
**Status:** Core seams landed and tested. Core now passes CI's own static
analysis. GUI work is blocked on `rcplatform-api` being unpublished, which also
blocks the packaged jar; see section 1.
**Purpose of this doc:** Everything a fresh session needs to pick this up without re-deriving it.

---

## 1. What is actually blocking the build

**Superseded.** The earlier diagnosis in this section blamed a sandbox egress
allowlist for `worldedit-core`, `worldedit-bukkit` and `paperlib`. That is no
longer true: all three resolve, every protection hook builds, and the reactor
now reaches `spigot-core` before it fails. The real blockers are two unrelated
regressions, both of which predate the wizard work.

### Blocker A: `rcplatform-api` is not published anywhere (open)

`animatedarchitecture-spigot/spigot-core/pom.xml` declares:

```xml
<groupId>net.republicraft.platform</groupId>
<artifactId>rcplatform-api</artifactId>
<version>1.0.0</version>
<scope>provided</scope>
```

No repository in any pom hosts it. Maven tries each declared repository in turn
and ends on jitpack, which answers `401 Unauthorized`. The dependency was added
in `2d7eef4` ("feat: publish RCPlatform door service") and has only ever
resolved from a local `mvn install` on the author's machine.

Consequences:

- `spigot-core` and everything downstream of it (`structures`, all nine
  structure types, `spigot-packager`, both integration-test modules) cannot be
  built by anyone else, CI included.
- `RCDoors.jar` has never been produced by CI. Every run since the workflow was
  added has failed.
- **The GUI wizard lives in `spigot-core`, so it cannot be compiled or shipped
  until this is resolved.**

Options, in order of preference:

1. Publish `rcplatform-api` to a repository the build can reach, add it to
   `spigot-core/pom.xml`, and give CI credentials via a `settings.xml` and
   repository secrets. Correct long-term answer.
2. Vendor a compile-only stub of the ~14 API types under a `provided`-scope
   module. Unblocks everyone with no credentials, but the signatures would be
   reconstructed from usage, so a mismatch with the real jar surfaces as a
   runtime `NoSuchMethodError` rather than a compile error.
3. Bind the door service reflectively and drop the compile-time dependency.
   Removes the blocker at the cost of type safety.

This needs a decision from the repo owner. It is not something a build fix can
route around.

### Blocker B: static analysis on `Creator.java` (fixed)

CI runs `mvn -P=errorprone clean test install checkstyle:checkstyle pmd:check`.
A plain `mvn compile` does not enable that profile, which is why the failure was
invisible locally. Under the profile, `animatedarchitecture-core` failed to
compile with three errors introduced by `bdecdfd` (the block-selection wand):

| Line | Check | Cause |
|---|---|---|
| 888 | NullAway | `setProperty` took a `@NonNull` value, but the full-cuboid case passes null to mean "store no mask" |
| 955, 957 | GuardedBy | `sendSelectionStatus()` read `blockSelection` from lazily evaluated text arguments, which are rendered after the method releases the lock |

Both are fixed on this branch. `setProperty` now accepts `@Nullable T`, which
`PropertyContainer.setPropertyValue` already supported, and
`sendSelectionStatus()` reads the selection eagerly and lets the text arguments
capture the results. The GuardedBy pair was a genuine race, not a false
positive.

### Verifying

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64

# Blocker B: passes on this branch.
mvn -B -P=errorprone -pl animatedarchitecture-core -am test pmd:check

# Blocker A: still fails at spigot-core with the 401 above.
mvn -B -pl animatedarchitecture-spigot/spigot-core -am -DskipTests compile
```

Always run the first command with `-P=errorprone`. Without it the build is not
running what CI runs, and this section's mistake repeats.

## 2. Environment notes

### JDK 25 is required to build

`pom.xml` line 14 sets `maven.compiler.release=25`. The default `JAVA_HOME` in
the container points at JDK 21, which fails with
`error: release version 25 not supported`. Every Maven invocation must export:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
```

Available JDKs: `/usr/lib/jvm/java-21-openjdk-amd64`, `/usr/lib/jvm/java-25-openjdk-amd64`.

### Open question: is JDK 25 correct for production?

This is a deployment risk, not a build issue. Paper 1.21.4 targets Java 21, and
commit `a620d38` moved this fork to Paper 26.2. If the RCHeists production host
runs anything below JDK 25, the plugin will throw
`UnsupportedClassVersionError` at server startup. That is a hard load failure,
not a degraded mode.

**Action required from the repo owner:** confirm the production JDK version.
If it is 21, lowering `maven.compiler.release` is a one-line change that should
happen before any feature work ships.

### Test baseline

- `animatedarchitecture-core`: **402 tests, all passing** with `-P=errorprone`,
  plus a clean `pmd:check`.
- `animatedarchitecture-testing`, `animatedarchitecture-integration-test`,
  and everything in the Spigot layer: **never executed**, because they are
  downstream of `spigot-core`, which is still blocked by Blocker A.

Do not describe the suite as fully green. Core is green. The Spigot layer is
unverified and has never been built by CI.

---

## 3. What the codebase actually is

A hard fork of AnimatedArchitecture (BigDoors 2). ~651 Java files, Dagger DI,
six Maven modules, nine structure types.

Fork history diverges from upstream at `b7c91f1` (2026-08-28). Upstream's last
commit is `e9dbef6` (2026-01-03), eight months stale. **A decision has been
made to treat this as a hard fork**, so upstream merge cost is no longer a
constraint on refactoring.

### The key insight

The wizard already exists. `Creator` + `Procedure` + `Step` + the ten
`StepExecutor*` classes are a complete, well-built step machine that already
supports:

- Named steps with typed executors (`Location`, `Boolean`, `Integer`,
  `String`, `OpenDirection`, `BlockSelection`)
- `skipCondition` for steps that vanish when irrelevant
- `updatable(true)` for revisiting a previous answer
- `propertyName` / `propertyValueSupplier`, so every step already knows how to
  describe itself as a label plus current value
- A review-and-confirm step before commit

Nobody ever wired a GUI to it. The only renderer is chat text with clickable
spans, which is why the plugin feels like clunky commands even though
structurally it is not.

**The correct fix is a second renderer over the same `Procedure`, not a
rewrite of the creation logic.** This keeps the nine structure types, the
animation engine, and the database layer completely untouched, which matters
because RCHeists depends on all three.

---

## 4. Work completed on this branch

Commit `f71396e`. Three additive seams, no behaviour change to the chat flow.
All 402 core tests pass.

### `Step.java`

Added `getPropertyName()`, `getPropertyValue()`, `isUpdatable()`, and
`reportsProperty()`.

Previously the only way to read a step's state was `getPropertyText()`, which
hardcodes the chat presentation, including a clickable
`/rcdoors UpdateCreator <step>` command. A GUI would have had to parse rendered
text. Now it can read the raw values and draw its own slot.

### `Creator.java`

Added `canUpdate()`, `beginEditingSession()`, `endEditingSession()`, and a
counted `editingSessionDepth` field.

**This solves a real blocker.** `Creator.update(stepName, value)` is the only
way to jump to an arbitrary step, and it was guarded by `processIsUpdatable`,
a flag set to `true` in exactly one place (`prepareReviewResult()`) and
immediately cleared by the first `update()` call. So the original code could
only edit a previous answer **once**, and **only** from the review screen.

A GUI where every step is a clickable slot needs that flag held open. The guard
was deliberate re-entrancy protection, not an accident, so it was gated rather
than deleted:

- With an editing session open, `update()` leaves the process updatable.
- With no session open, `update()` clears the flag exactly as before, so the
  chat review step keeps its original single-shot behaviour.

Sessions are counted, so nesting is safe.

### `ToolUser.java`

Added `IProcedureListener` with `onStepChanged` and `onProcedureShutDown`,
plus `addProcedureListener` / `removeProcedureListener`.

Fired from `prepareCurrentStep()`, which is the single funnel every procedure
advance passes through, and from `cleanUpProcess()`.

This exists because most creation steps require an in-world click that an open
inventory cannot capture. The wizard must close, let the player click, then
reopen. It needs a push notification to know when to reopen; polling would be
wrong. Listener exceptions are caught, logged, and swallowed so that a broken
front-end can never break structure creation.

---

## 5. Next: the GUI wizard

### Design

`WizardGui` in
`animatedarchitecture-spigot/spigot-core/src/main/java/nl/pim16aap2/animatedarchitecture/spigot/core/gui/`,
implementing `IGuiPage`, following the existing `MainGui` / `CreateStructureGui`
conventions (`InventoryGui` from `de.themoep.inventorygui`, Dagger
`@AssistedInject` plus an `@AssistedFactory IFactory`).

It is a **hub, not a replacement**. Because in-world steps need the inventory
closed, the loop is:

1. Wizard shows all steps as slots, with name, current value, and completion state.
2. Player clicks a slot. Wizard calls `creator.update(stepName, null)` and closes.
3. Player performs the in-world action (click a block, select a region).
4. `IProcedureListener.onStepChanged` fires. Wizard reopens, refreshed.

Bracket the whole interaction with `beginEditingSession()` on open and
`endEditingSession()` on close.

### Implementation checklist

- [ ] `WizardGui` class plus `IFactory`, registered in `GuiFactorySpigotModule`
- [ ] Slot rendering driven by `Step.reportsProperty()` / `getPropertyName()` / `getPropertyValue()`
- [ ] Distinct materials for completed / current / pending / non-updatable steps
- [ ] Click handler calling `Creator.update()`, guarded by `canUpdate()`
- [ ] `beginEditingSession()` on open, `endEditingSession()` on close action
- [ ] Register / unregister `IProcedureListener`, mirroring how
      `GuiStructureDeletionManager` handles listener lifecycle in `MainGui`
- [ ] Anvil GUI for the name step, replacing the `/rcdoors setname` chat command
- [ ] Confirm and cancel controls, reusing the existing `Confirm` / `Cancel` commands
- [ ] Entry point: open the wizard when a creation process starts, from
      `CreateStructureGui`'s click handler
- [ ] Localization keys in `SpigotCore.properties`
- [ ] **Config flag defaulting to off**, so the chat flow stays the default
      path until the GUI is proven on the heist server

### Threading

`InventoryGui` calls must happen on the main server thread. `GuiFactory`
already demonstrates the pattern with `executor.runOnMainThread(...)`.
`IProcedureListener` callbacks fire from whatever thread advanced the
procedure, which for async steps is not the main thread. **Every listener
callback must hop to the main thread before touching the GUI.** This is the
most likely source of subtle bugs in this work.

---

## 6. Backlog beyond the GUI

Ordered by user-visible value, not by ease.

### Phase 2: block selection that is not torture

`SELECT_BLOCKS` currently requires clicking every single block individually.
For a heist vault door this is the single biggest time sink. `BlockSelection`
and `BlockSelectionBuilder` already exist with undo support; what is missing
are region operators: fill, hollow, layer, sphere, replace-material.

### Phase 3: collapse the `*Delayed` command boilerplate

`SetOpenStatusDelayed`, `SetBlocksToMoveDelayed`, `SetOpenDirectionDelayed`,
and `SetProximityDelayed` are 45 to 48 lines each and, with names normalized,
differ **only** in their generic type parameter and their javadoc. That is
roughly 180 lines of pure duplication that should be one parameterized factory
call.

Worth doing, but be honest about it: this is housekeeping. It changes nothing
for the people building doors.

### Phase 4: other known clunk

| Pain | Cause |
|---|---|
| Power block is a whole separate concept to learn | `SET_POWER_BLOCK_POS` step plus `PowerBlockRelocator` and `PowerBlockInspector` tools |
| Open direction is a list of clickable words in chat | `creator.base.set_open_direction` |
| Hard 15 minute timeout mid-build | `creator.base.init` |

---

## 7. Standing constraints

1. **RCHeists depends on this plugin.** Prefer additive changes. Anything that
   touches structure creation, the animation engine, or the database layer
   needs a proven-safe argument, not just passing tests.
2. **Never claim a build is green without reading the log.** Background task
   notifications report the wrapper's exit code, not Maven's. Grep the log for
   `BUILD SUCCESS` or an explicit `EXIT=` marker.
3. **State test coverage precisely.** "402 core tests pass" is true. "Tests
   pass" implies Spigot coverage that does not currently exist.
