# Simple Gym Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Simple Gym Android app described in the spec: a workout logger that tells the lifter what to lift, with swaps, any-order exercises, a one-line log, an EMOM timer, an over-40 coaching engine, a mobility add-on, progress charts and plan editing.

**Architecture:**
- **Two Gradle modules.** `:engine` is pure Kotlin/JVM and holds every coaching, timer and routine rule, with exhaustive JUnit tests. `:app` is Android (Compose + Material 3 + Room). It turns database rows into engine inputs, renders screens and runs the timer as a foreground service.
- **No DI framework.** An `AppContainer` on the `Application` wires the database, services and settings.
- **Milestone order.** Milestone 1 ships the daily gym loop to the phone first, then coaching depth (M2), then extras (M3). Every milestone ends with an installable signed APK.

**Tech Stack:**

| Component | Version |
|---|---|
| JDK | 21 (Microsoft OpenJDK) |
| Gradle | 9.8.2 |
| AGP | 9.4.1 (built-in Kotlin) |
| Kotlin | 2.4.21 |
| KSP | 2.3.12 |
| Compose BOM | 2026.09.00 |
| Room 3 (androidx.room3) | 3.0.3 |
| androidx.sqlite (driver) | 2.7.1 |
| Navigation Compose | 2.10.2 |
| activity-compose | 1.13.0 |
| lifecycle | 2.11.0 |
| core-ktx | 1.19.1 |
| kotlinx-serialization-json | 1.12.0 |
| kotlinx-coroutines | 1.11.0 |
| JUnit | 4.13.2 |
| Truth | 1.4.5 |
| Robolectric | 4.17 |
| androidx.test.ext:junit | 1.3.0 |

- **Charts:** a custom Compose `Canvas` line chart, not a chart library.

**Spec:** `docs/specs/2026-10-22-simple-gym-design.md`. Section references like "§6.2" point there. Read it alongside this plan.

## Global Constraints

### Build and platform

- **SDK levels:** `minSdk = 31`. `compileSdk = 37` and `targetSdk = 37`. Fallback rule in Task 1: 36 for both.
- **Package and app ID:** `com.simplegym` for both `applicationId` and the root package. The engine package is `com.simplegym.engine`.
- **Permissions:** no `INTERNET`. Allowed: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `VIBRATE`.
- **Signing:** every APK installed on the phone, debug included, is signed with the one release keystore from Task 1. A signature change forces an uninstall, which wipes the lifter's data.

### Repo and privacy

- **The public repo must contain no secrets and no personal data.**
  - **Never committed:** keystores and passwords (in git-ignored `keystore.properties`), plus the lifter's age, health notes, weights, gym names or work email.
  - **Pre-push check:** `scripts/check-before-push.sh` runs before every push.
- **Commit identity:** commits use the repo-local identity already configured: `BlueFluxo <88369563+BlueFluxo@users.noreply.github.com>`. Do not override it. End every commit message with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

### Look and behaviour

- **Theme:** dark-only. Background `#0F1115`, surface `#1A1D23`, accent `#FF7A1A`, warning/hurt amber `#FFB020`. Big numerals, touch targets ≥ 56dp.
- **Units:** kg. Weights display without trailing `.0` (60, 62.5, −70).
- **Tap rule (§5.12):**
  - Open app → lifting is ≤ 2 taps.
  - A swap, starting the timer, and jumping to any exercise are 1 tap each.
  - No modal dialogs except on first launch and destructive confirmations (delete, restore).
  - Stiff and hurt handling are inline banners or toggles.
- **No nagging:** no notifications other than the running timer, and no frequency, streak or missed-day reminders.

### Coaching engine

- **Purity:** engine functions are pure and deterministic. They take `today: LocalDate` as a parameter and never read the clock.

## Review Focus

1. **An in-progress session killed by Samsung's background limits or process death.** The lifter expects to reopen the app and find today's session: done, skipped and pending states plus saved logs, with a "Resume" button on Home. Pinned in Task 8.
2. **The timer service killed mid-EMOM.** The lifter expects the timer to resume at the correct round, because time is computed from `SystemClock.elapsedRealtime()`, and the battery-settings card to reappear. Pinned in Task 11.
3. **Editing or deleting a day or slot mid-round.** Round progress must not jam or jump: completion uses the *current* active days and each session's slot count *at start*. Logs of deleted slots survive. Pinned in Tasks 15 and 23.
4. **Deleting an exercise that has history.** It is archived (hidden from chips and search), not deleted. Its logs and progress chart remain. Pinned in Task 23.
5. **Odd log input.**
   - Typing `70` on an assisted exercise is stored as −70.
   - Reps or sets of 0 or blank are rejected with an inline error.
   - A weight on a bodyweight exercise is ignored.

   Pinned in Task 12.

---

## Milestone 0: Toolchain

### Task 1: Toolchain, project skeleton, signing, pre-push check, first push

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, the Gradle wrapper files (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`)
- Create: `engine/build.gradle.kts`, `engine/src/main/kotlin/com/simplegym/engine/EngineInfo.kt`, `engine/src/test/kotlin/com/simplegym/engine/EngineInfoTest.kt`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/simplegym/MainActivity.kt`, `app/src/test/resources/robolectric.properties`, `app/src/test/kotlin/com/simplegym/MainActivityTest.kt`
- Create: `scripts/check-before-push.sh`, `scripts/install-hooks.sh`
- Create (git-ignored, never committed): `local.properties`, `keystore.properties`, `personal/markers.txt`

**Interfaces:**
- Produces: `const val ENGINE_VERSION = 1` in `EngineInfo.kt`, plus the `./gradlew` commands every later task uses:
  - `./gradlew :engine:test`
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:assembleRelease`

- [ ] **Step 1: Install the JDK and Android SDK.**
  1. Run `winget install --id Microsoft.OpenJDK.21 -e --silent`.
  2. Download the latest `commandlinetools-win-*_latest.zip` (currently `16111833`) from `https://dl.google.com/android/repository/`. Unzip it to `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`.
  3. Run `sdkmanager --licenses`, accepting all, then `sdkmanager "platform-tools" "platforms;android-37.0" "platforms;android-36"`.
  4. Persist `JAVA_HOME` and `ANDROID_HOME` with `setx`.
  5. Write `local.properties` with `sdk.dir=` set to the SDK path, using forward slashes.

  Verify: `java -version` prints 21, and `"$ANDROID_HOME/platform-tools/adb" version` prints a version.

- [ ] **Step 2: Create the Gradle wrapper.** Download `gradle-9.8.2-bin.zip` to `%USERPROFILE%\.tools\` and unzip it there. From the repo root run `<that>/bin/gradle wrapper --gradle-version 9.8.2 --distribution-type bin`.

  Verify: `./gradlew --version` shows Gradle 9.8.2.

- [ ] **Step 3: Write the version catalog and build files** with the pinned versions from the Tech Stack.
  - **AGP 9 built-in Kotlin:** do **not** apply `org.jetbrains.kotlin.android`. Configure compiler options in `kotlin { compilerOptions { } }`.
  - **Plugins:** apply `org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization` (version = kotlin) and `com.google.devtools.ksp` to `:app`. `:engine` uses `org.jetbrains.kotlin.jvm` (version = kotlin), JVM toolchain 21.
  - **`:app`:** `testOptions.unitTests.isIncludeAndroidResources = true`, and `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`.
  - **`robolectric.properties`:** `sdk=36`.
  - **Dependencies:**
    - Compose BOM: ui, material3, ui-tooling-preview, and `ui-test-manifest` as `debugImplementation`.
    - Room 3 runtime (`androidx.room3`, plugin `androidx.room3`) with the compiler on `ksp`, and `androidx.sqlite:sqlite-framework` for `AndroidSQLiteDriver`. (Originally Room 2.8.5; migrated to Room 3 in Task 11b.)
    - navigation-compose, activity-compose, lifecycle-runtime-compose, lifecycle-viewmodel-compose, core-ktx, serialization-json, coroutines-android.
    - `:engine` as a project dependency.
    - Tests: junit, truth, robolectric, `ui-test-junit4`, `androidx.test.ext:junit`, coroutines-test, room-testing.
  - Check the AGP 9.0 release notes (`developer.android.com/build/releases/agp-9-0-0-release-notes`) for how the Compose compiler and serialization plugins combine with built-in Kotlin, and follow them.

- [ ] **Step 4: Apply the fallback rules if the build fails, then record any fallback in the spec's §3.**
  - **compileSdk:** if AGP rejects compileSdk/targetSdk 37, use 36.
  - **KSP / Room:** if KSP 2.3.12 or Room fails against Kotlin 2.4.21, lower Kotlin to the newest version listed as supported in the KSP 2.3.12 release notes.
  - **Robolectric:** if it fails on `sdk=36`, use `sdk=35`.

  Don't improvise beyond these rules. If none of them resolves the failure, stop and report.

- [ ] **Step 5: Write the failing tests.**

```kotlin
// EngineInfoTest
@Test fun engineVersionIsOne() { assertThat(ENGINE_VERSION).isEqualTo(1) }
// MainActivityTest (Robolectric + createAndroidComposeRule<MainActivity>())
@Test fun showsAppName() { rule.onNodeWithText("Simple Gym").assertIsDisplayed() }
```

- [ ] **Step 6: Run them.** `./gradlew :engine:test :app:testDebugUnitTest`. Expected: FAIL, because `ENGINE_VERSION` and `MainActivity` are missing.

- [ ] **Step 7: Implement.** Add `EngineInfo.kt`, and a `MainActivity` that calls `setContent { Text("Simple Gym") }`. Add a manifest with no permissions yet and `android:theme` set to a dark no-action-bar theme.

- [ ] **Step 8: Run the tests and the build.** `./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Set up signing.**
  1. Run `keytool -genkeypair -v -keystore "%USERPROFILE%\.android\simple-gym-release.jks" -alias simplegym -keyalg RSA -keysize 4096 -validity 10000` with a generated 24-char password and dname `CN=Simple Gym`.
  2. Write `keystore.properties` (git-ignored) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
  3. In `app/build.gradle.kts`, read `keystore.properties` if it exists and apply that signing config to **both** `debug` and `release` build types. If the file is absent, leave the defaults, so the open-source build still works.

  Verify: `./gradlew :app:assembleRelease` produces `app/build/outputs/apk/release/app-release.apk`, and `apksigner verify --print-certs` shows `CN=Simple Gym`.

- [ ] **Step 10: Write `scripts/check-before-push.sh` (bash).** It scans `git ls-files` contents and exits non-zero on any hit, printing file:line.
  - **(a) Secret patterns:** `-----BEGIN .*PRIVATE KEY`, `(store|key)Password\s*=`, `AKIA[0-9A-Z]{16}`, `gh[pousr]_[A-Za-z0-9]{30,}`.
  - **(b) Forbidden tracked files:** paths matching `*.jks|*.keystore|keystore.properties|local.properties|personal/*`.
  - **(c) Personal markers:** each non-empty line of `personal/markers.txt` (if present), matched case-insensitively as a fixed string.
  - **Hook:** `scripts/install-hooks.sh` installs it as `.git/hooks/pre-push`.
  - **Markers file:** create `personal/markers.txt` (git-ignored) with one marker per line: the lifter's first name, work email domain, both club suburbs and the gym chain name. Take these from the brainstorming session; the main session fills it if the executor lacks them.

  Verify the check catches problems:
  1. Temporarily `git add -f` a copy of `keystore.properties`, run the script and expect a non-zero exit, then `git rm --cached` it.
  2. Run it clean and expect exit 0.

- [ ] **Step 11: Commit and push.**

```bash
git add -A && git commit -m "build: project skeleton, signing config, pre-push check"
bash scripts/install-hooks.sh && git push -u origin main
```

Expected: the push succeeds, with the hook run shown in the output.

---

## Milestone 1: The daily gym loop

### Task 2: Engine model and load maths

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/Model.kt`, `engine/src/main/kotlin/com/simplegym/engine/Load.kt`
- Test: `engine/src/test/kotlin/com/simplegym/engine/LoadTest.kt`

**Interfaces (Produces — used by every later engine task):**

```kotlin
enum class Feel { EASY, SOLID, GRINDER }
enum class SessionType { NORMAL, DELOAD, FLUSH, HELD, RETURN }
enum class RangeType { BASE, HIGH_REP }
enum class Joint { SHOULDER, ELBOW, WRIST, KNEE, HIP, LOWER_BACK, NECK }
enum class BackLoad { LOW, MODERATE, HIGH }
enum class Muscle { CHEST, BACK, SHOULDERS, BICEPS, TRICEPS, QUADS, HAMSTRINGS, GLUTES, CALVES, CORE, FOREARMS, MOBILITY }
enum class Chip(val muscles: Set<Muscle>) // BACK{BACK} BICEPS{BICEPS,FOREARMS} CHEST{CHEST} TRICEPS{TRICEPS}
    // SHOULDERS{SHOULDERS} LEGS{QUADS,HAMSTRINGS,GLUTES} CALVES{CALVES} LOWER_BACK{} NECK{}
data class RepRange(val lo: Int, val hi: Int)
data class Exercise(val id: String, val name: String, val muscle: Muscle, val equipment: String,
    val swapGroup: String, val incrementKg: Double?, val backLoad: BackLoad, val joints: Set<Joint>,
    val isAssisted: Boolean, val isBodyweight: Boolean)
data class LogRecord(val exerciseId: String, val date: LocalDate, val weightKg: Double?, val bestReps: Int,
    val sets: Int, val feel: Feel, val type: SessionType, val rangeType: RangeType = RangeType.BASE,
    val hurt: Joint? = null)
val LogRecord.isBaseline: Boolean   // (type == NORMAL || type == RETURN) && hurt == null
data class WeightHint(val exerciseName: String, val weightKg: Double)
data class Target(val weightKg: Double?, val sets: Int, val range: RepRange, val repGoal: Int?,
    val type: SessionType, val reason: String, val hint: WeightHint? = null,
    val rangeType: RangeType = RangeType.BASE, val roundSec: Int = 120)

fun stepUp(weightKg: Double, stepKg: Double, steps: Int = 1, assisted: Boolean): Double   // + steps*step; assisted clamps at 0.0
fun stepDown(weightKg: Double, stepKg: Double, assisted: Boolean): Double                 // − step; non-assisted clamps at 0.0
fun reduceBy(weightKg: Double, fraction: Double, stepKg: Double, assisted: Boolean): Double
fun roundToStep(weightKg: Double, stepKg: Double): Double                                 // nearest multiple
fun formatKg(weightKg: Double): String                                                    // "60", "62.5", "−70"
```

**`reduceBy` semantics:**
- **Non-assisted:** `w × (1 − f)`.
- **Assisted:** `w × (1 + f)`, i.e. more assistance.
- **Rounding:** the result is floored to a multiple of the step (toward lighter in both cases). This is my call: reductions round toward lighter.

- [ ] **Step 1: Write the failing tests** in `LoadTest`.
  - `stepUp(60.0, 2.5, assisted=false) == 62.5`
  - `stepUp(60.0, 2.5, 2, false) == 65.0`
  - `stepUp(-70.0, 2.5, assisted=true) == -67.5`
  - `stepUp(-2.5, 2.5, 2, true) == 0.0` (clamped at 0)
  - `stepDown(60.0, 2.5, false) == 57.5`
  - `stepDown(-70.0, 2.5, true) == -72.5`
  - `stepDown(2.5, 5.0, false) == 0.0`
  - `reduceBy(60.0, 0.10, 2.5, false) == 52.5` (54 floored)
  - `reduceBy(-70.0, 0.10, 2.5, true) == -77.5`
  - `reduceBy(62.5, 0.20, 2.5, false) == 50.0`
  - `roundToStep(54.78, 2.5) == 55.0`
  - `formatKg(60.0) == "60"`, `formatKg(62.5) == "62.5"`, `formatKg(-70.0) == "−70"` (U+2212 minus)

- [ ] **Step 2: Run them.** `./gradlew :engine:test --tests '*LoadTest*'`. Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement** `Model.kt` and `Load.kt` per the interfaces. Use `kotlin.math.floor` on `w/step` with an epsilon of `1e-9` so exact multiples stay put.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "engine: model types and load maths"`

### Task 3: Progression table (§6.2)

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/Progression.kt`
- Test: `engine/src/test/kotlin/com/simplegym/engine/ProgressionTest.kt`

**Interfaces:**
- Consumes: Task 2 types and `stepUp`, `stepDown`, `reduceBy`.
- Produces:
  - `data class Progressed(val weightKg: Double?, val repGoal: Int?, val reason: String)`
  - `fun progress(base: LogRecord, range: RepRange, exercise: Exercise, maxStepsPerSession: Int): Progressed`

**Rules:**
- **The step** is `exercise.incrementKg`. The caller passes an `Exercise` with the effective increment already applied.
- **Bodyweight** (`incrementKg == null` or `isBodyweight`): the weight stays `null`. The rep goal follows the same table, with `+1 rep` in place of a weight step and capped at `hi`. At `R ≥ hi` and Easy, the reason is `"Top of the range is easy: try a harder variation or add load."`

Tests use `range 8–12`, `step 2.5` and `W = 60` unless stated.

- [ ] **Step 1: Write the failing tests.** One `@Test` per row.

| Test name | Input | Expected weight, repGoal |
|---|---|---|
| `hiPlus3EasyJumpsTwoSteps` | R=15 EASY, maxSteps=2 | 65.0, null |
| `hiPlus3EasyCappedAtOneStepForMax1` | R=15 EASY, maxSteps=1 | 62.5, null |
| `topOfRangeSolidAddsStep` | R=12 SOLID | 62.5, null |
| `topOfRangeEasyAddsStep` | R=13 EASY | 62.5, null |
| `topOfRangeGrinderHolds` | R=12 GRINDER | 60.0, 12 |
| `inRangeHoldsAndAddsRep` | R=10 SOLID | 60.0, 11 |
| `belowRangeDropsStep` | R=7 SOLID | 57.5, null |
| `farBelowRangeDropsTenPercent` | R=5 SOLID | 52.5, null |
| `belowRangeGrinderDropsTenPercent` | R=7 GRINDER | 52.5, null |
| `assistedTopOfRangeMovesTowardZero` | W=−70, assisted, R=12 SOLID | −67.5, null |
| `assistedBelowRangeAddsAssistance` | W=−70, assisted, R=7 SOLID | −72.5, null |
| `bodyweightInRangeAddsRep` | weight null, bodyweight, R=10 | null, 11 |

  Also assert that each reason is non-empty, and that `topOfRangeSolidAddsStep`'s reason contains `"+2.5kg"`.

- [ ] **Step 2: Run them.** `./gradlew :engine:test --tests '*ProgressionTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement `progress`.** Evaluate rows top-down, first match wins.
  - **Increase reasons** follow `"+<kg>kg: you hit <R> <feel> last time"`, e.g. `"+2.5kg: you hit 12 solid last time"`.
  - **Hold and drop reasons** state the rule in plain words.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "engine: progression table"`

### Task 4: Library and starter-plan assets

**Files:**
- Move: `docs/research/exercise-library.json` → `app/src/main/assets/exercise-library.json`
- Modify: the spec's §4 path reference
- Create: `app/src/main/assets/starter-plan.json`
- Create: `app/src/main/kotlin/com/simplegym/data/Slug.kt`, `app/src/main/kotlin/com/simplegym/data/AssetModels.kt`
- Test: `app/src/test/kotlin/com/simplegym/data/AssetsTest.kt`

**Interfaces:**
- Produces:
  - `fun slug(name: String): String`: lowercase, runs of non-alphanumerics → `-`, trimmed of `-`.
  - `@Serializable` asset models: `LibraryFile(val exercises: List<LibraryExercise>)`, where `LibraryExercise` adds `id: String` and `stressesJoints: List<Joint>` to the existing fields; and `StarterPlanFile(val days: List<StarterDay>)`.
    - `StarterDay(name, chips: List<Chip>, slots: List<StarterSlot>)`
    - `StarterSlot(exercise: String, sets, lo, hi, roundSec = 120, swaps: List<String>)`
  - `fun LibraryExercise.toEngine(): Exercise`

- [ ] **Step 1: Write the failing tests** in `AssetsTest` (reading the assets via a Robolectric context).
  - `slug("Hip abductor machine (out)") == "hip-abductor-machine-out"`
  - The library has 142 exercises with unique ids, each `id == slug(name)`.
  - Every exercise has `stressesJoints` present. Spot-check `"Bench press"` ⊇ {SHOULDER, ELBOW}, `"Barbell back squat"` ⊇ {KNEE}, and `"Chin tucks"` ⊇ {NECK}.
  - The starter plan has 3 days named `"Chest / Triceps"`, `"Back / Biceps"` and `"Legs / Calves"`, with 5, 7 and 8 slots.
  - Every starter `exercise` and `swaps` name resolves to a library name.
  - No starter slot or swap has `backLoad == HIGH`.
  - Day chips are {CHEST, TRICEPS, SHOULDERS}, {BACK, BICEPS} and {LEGS, CALVES}.
- [ ] **Step 2: Run them.** `./gradlew :app:testDebugUnitTest --tests '*AssetsTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Move the library and add `id` and `stressesJoints` to every entry. `stressesJoints` is derived by reading each entry's `jointNotes`; map the words "shoulder", "elbow", "wrist", "knee", "hip", "lower back", "neck" to joints, and review each by hand.
  - Write `starter-plan.json` exactly from spec §7: names, sets × reps, swap order.
  - Write `Slug.kt` and `AssetModels.kt`.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "data: exercise library and starter plan assets"`

### Task 5: Room database, seeding, settings

**Files:**
- Create: `app/src/main/kotlin/com/simplegym/data/Entities.kt`, `Daos.kt`, `Converters.kt`, `GymDatabase.kt`, `Seeder.kt`
- Create: `app/src/main/kotlin/com/simplegym/AppContainer.kt`, `app/src/main/kotlin/com/simplegym/SimpleGymApp.kt`
- Test: `app/src/test/kotlin/com/simplegym/data/SeederTest.kt`

**Interfaces (Produces):** entities, all in `Entities.kt`.

```kotlin
ExerciseEntity(id: String PK, name, muscle: Muscle, equipment, swapGroup, incrementKg: Double?, incrementOverrideKg: Double?,
    backLoad: BackLoad, joints: Set<Joint>, isAssisted, isBodyweight, isCustom: Boolean, archived: Boolean, hurtClearedOn: LocalDate?)
DayEntity(id: Long PK auto, name, position: Int, chips: Set<Chip>, archived: Boolean)
SlotEntity(id: Long PK auto, dayId FK→Day CASCADE, position, defaultExerciseId, sets, lo, hi, roundSec,
    timerRounds: Int?, timerRoundSec: Int?, rotationExerciseId: String?, rotationBlockIndex: Int?,
    sessionOnlyFor: Long? /* non-null = a flush-finisher slot for that session only (Task 20); excluded from plans,
                             from SessionEntity.slotsTotal and from slotsLogged round counting */)
SlotSwapEntity(slotId FK CASCADE, exerciseId, position)  // PK(slotId, exerciseId)
SessionEntity(id: Long PK auto, dayId, date: LocalDate, startedAtMillis: Long, finishedAtMillis: Long?, clubId: Long?, slotsTotal: Int)
SessionSlotEntity(sessionId FK CASCADE, slotId, status: SlotStatus /*PENDING, DONE, SKIPPED*/, flush: Boolean) // PK(sessionId, slotId)
LogEntity(id: Long PK auto, sessionId: Long?, slotId: Long? /*FK SET_NULL*/, exerciseId, date: LocalDate, weightKg: Double?,
    bestReps, sets, feel: Feel, type: SessionType, rangeType: RangeType, hurt: Joint?, note: String?, imported: Boolean)
CheckInEntity(date: LocalDate PK, stiff: Set<Chip>)
ClubEntity(id: Long PK auto, name); ClubUnavailableEntity(clubId FK CASCADE, exerciseId) // PK both
MobilityDoneEntity(id auto, area: String, date: LocalDate)
SettingsEntity(id: Int PK = 0, age: Int?, lowerBackProtection: Boolean, currentClubId: Long?, equipmentIncrements: Map<String, Double>,
    onboarded: Boolean, batteryCardDismissed: Boolean, timerWasKilled: Boolean)
```

- **DAOs:** one per aggregate (`LibraryDao`, `PlanDao`, `SessionDao`, `LogDao`, `SettingsDao`, `ClubDao`), with suspend writes and `Flow` reads.
- **`fun LogEntity.toRecord(): LogRecord`**
- **`class Seeder(db, assets)`:** `suspend fun seedIfEmpty()` inserts the library, the starter plan and a default `SettingsEntity`. It's idempotent.
- **`AppContainer(context)`** exposes `db` and later services. `SimpleGymApp` holds it.

- [ ] **Step 1: Write the failing tests** in `SeederTest` (in-memory Room, Robolectric).
  - `seedIfEmpty()` inserts 142 exercises, 3 days and 20 slots, and the Bench press slot has 4 swaps in spec order.
  - Running it twice leaves the counts unchanged.
  - Deleting a slot sets `LogEntity.slotId` to null and keeps the log.
  - `SettingsEntity` defaults are: age null, lowerBackProtection false, onboarded false.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** `Converters` store sets as comma-joined enum names, `LocalDate` as epoch-day `Long` and maps as JSON. Set `exportSchema = true`; the schema JSON under `app/schemas` is committed.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "data: room database and seeding"`

### Task 6: TargetService v1: variant memory, progression, cold start

**Files:**
- Create: `app/src/main/kotlin/com/simplegym/domain/TargetService.kt`, `app/src/main/kotlin/com/simplegym/domain/Increments.kt`
- Test: `app/src/test/kotlin/com/simplegym/domain/TargetServiceTest.kt`

**Interfaces:**
- Consumes: DAOs (Task 5), `progress` (Task 3).
- Produces:
  - `fun effectiveIncrement(e: ExerciseEntity, equipmentIncrements: Map<String, Double>): Double?` = `incrementOverrideKg ?: equipmentIncrements[equipment] ?: incrementKg`
  - `fun ExerciseEntity.toEngine(equipmentIncrements: Map<String, Double>): Exercise`, with `incrementKg = effectiveIncrement(...)`. Every app-side call into the engine uses this mapper.
  - `class TargetService(db)` with `suspend fun targetFor(slotId: Long, exerciseId: String, today: LocalDate): Target`. Task 19 rewrites the body to call `coachTarget`, but the signature stays.
  - `suspend fun swapChipWeights(exerciseIds: List<String>): Map<String, Double?>`: the last baseline weight per exercise, or null for "new".

**v1 rules:**
- **Baseline:** the last baseline log for this exercise (`LogRecord.isBaseline`). Then `progress(base, slotRange, exercise, maxStepsPerSession = 2)`.
- **No baseline → cold start (§6.12):** `weightKg = null`, reason `"New: start light, with a weight you could lift about 15 times."`, and `hint` = the last baseline of another exercise with the same `swapGroup` **and** `equipment`, if any.
- **Sets** = the slot's sets, `roundSec` = the slot's roundSec, `rangeType = BASE`.

- [ ] **Step 1: Write the failing tests.**
  - `effectiveIncrement` precedence: an override of 1.0 beats a map entry of 2.0, which beats a library value of 2.5.
  - A Bench press baseline of 60 × 12 SOLID gives a target of 62.5 for 3 sets, range 8–12.
  - An exercise with no logs gives weight null, the cold-start reason, and a hint of `WeightHint("Dumbbell bench press", 22.5)` when that exercise (same group, same equipment `Dumbbell`) has a baseline. A barbell baseline gives no hint for a dumbbell exercise.
  - A HELD log after a NORMAL one is ignored: the target derives from the NORMAL one.
  - A log with `hurt` set is ignored as a baseline.
  - `swapChipWeights` returns null for an exercise with no history.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "domain: target service v1"`

### Task 7: Theme, navigation, Home v1, session service

**Files:**
- Create: `app/src/main/kotlin/com/simplegym/ui/theme/Theme.kt`, `ui/nav/Routes.kt`, `ui/nav/GymNavHost.kt`, `ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt`
- Create: `app/src/main/kotlin/com/simplegym/domain/SessionService.kt`
- Modify: `MainActivity.kt` (host `GymNavHost`)
- Test: `app/src/test/kotlin/com/simplegym/domain/SessionServiceTest.kt`, `app/src/test/kotlin/com/simplegym/ui/home/HomeScreenTest.kt`

**Interfaces (Produces):**
- **Routes:** `@Serializable` objects/classes `Home`, `Workout(sessionId: Long)`, `ExerciseRoute(sessionId: Long, slotId: Long)`, `TimerRoute(sessionId: Long, slotId: Long, exerciseId: String)`, `LogRoute(sessionId: Long, slotId: Long, exerciseId: String)`, `Mobility(sessionId: Long?)`, `StandaloneTimer`, `Progress`, `Plans`, `Settings`, `Onboarding`.
- **`class SessionService(db, clock: () -> Long)`:**
  - `suspend fun start(dayId: Long, today: LocalDate, clubId: Long?): Long`
  - `suspend fun activeSession(nowMillis: Long): SessionEntity?`: unfinished and started < 12h ago.
  - `suspend fun closeStale(nowMillis: Long)`: finishes unfinished sessions ≥ 12h old.
  - `suspend fun setStatus(sessionId: Long, slotId: Long, status: SlotStatus)`
  - `suspend fun setFlush(sessionId: Long, slotId: Long, flush: Boolean)`
  - `suspend fun finish(sessionId: Long)`
  - `fun slotStates(sessionId: Long): Flow<List<SessionSlotEntity>>`
- **HomeViewModel state:** `HomeState(days: List<DayCardUi>, suggestedDayId: Long?, activeSessionId: Long?, header: String)`, where `DayCardUi(dayId: Long, name: String, lastDoneText: String /* "Last done 4 days ago" | "Not done yet" */, statusText: String?)`.
- **Club on start:** Home passes `settings.currentClubId` to `start`.
- **Home entry points:**
  - Every day card has its own one-tap start, not just the suggested day.
  - A top app bar has icon buttons `Timer`, `Progress`, `Plans`, `Settings` that navigate to their routes. Until their tasks land, they go to placeholder screens showing `"Coming soon"`.
  - **Test:** tapping the Legs / Calves card's start starts that day, and each of the 4 app-bar buttons navigates.
- **v1 suggestion:** the day with the oldest `lastDone` (null first). The header is empty until Task 15.

- [ ] **Step 1: Write the failing tests.**
  - `start` creates one `SessionSlotEntity` per slot, all PENDING, with `slotsTotal` = the slot count.
  - `activeSession` returns it at +1h and null at +13h.
  - `closeStale` sets `finishedAtMillis`.
  - HomeScreen (Robolectric compose, seeded DB) shows the 3 day names. The button `"Start Chest / Triceps"` is shown when that day is oldest. When an active session exists, `"Resume Chest / Triceps"` replaces it.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `Theme.kt` uses the colours from Global Constraints with `darkColorScheme` only.
  - Clicking Start calls `start` and then navigates to `Workout(sessionId)`.
  - `MainActivity` calls `closeStale` on create.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "ui: theme, navigation, home v1, session service"`

### Task 8: Workout list (any order, skip, finish, resume)

**Files:**
- Create: `app/src/main/kotlin/com/simplegym/ui/workout/WorkoutScreen.kt`, `WorkoutViewModel.kt`
- Test: `app/src/test/kotlin/com/simplegym/ui/workout/WorkoutScreenTest.kt`

**Interfaces:**
- Consumes: `SessionService`, `TargetService.targetFor`.
- Produces: `WorkoutState(dayName, rows: List<SlotRowUi>, nextSlotId: Long?, doneCount, total)`, where `SlotRowUi(slotId, exerciseName, status, summary: String, isNext: Boolean)`.
  - **Done rows:** the summary is `"<logged kg>"`.
  - **Pending rows:** the summary is `"<sets> × <lo>–<hi> @ <target kg | new>"`.
  - **Skipped rows:** the summary is `"skipped · come back"`.
- **Next slot:** the first PENDING slot in position order. If no slot is PENDING, the first SKIPPED one.

- [ ] **Step 1: Write the failing tests.**
  - All 5 Chest/Triceps rows are shown. The first row is marked next and has a `"▶ Start timer"` button.
  - Clicking a non-next row navigates to `ExerciseRoute` for that slot.
  - **Process death (Review Focus 1):** mark slot 1 SKIPPED and slot 2 DONE (with a log), then build a new ViewModel on the same DB. Slot 1 shows `"skipped · come back"`, slot 2 is struck through, and slot 3 is next.
  - `"Finish workout"` sets `finishedAtMillis`, turns leftover PENDING slots into SKIPPED (with no logs, §5.2), and returns to Home.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `"▶ Start timer"` on the next row navigates straight to `TimerRoute` with the slot's current default exercise. This is the 2nd tap of the tap rule.
  - The struck-through style uses `TextDecoration.LineThrough` at 45% alpha. Skipped rows have a 3dp amber start border.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "ui: workout list"`

### Task 9: Exercise screen (target card, swap chips, search, lower-back filter)

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/Swaps.kt`, `engine/src/test/kotlin/com/simplegym/engine/SwapsTest.kt`
- Create: `app/src/main/kotlin/com/simplegym/ui/exercise/ExerciseScreen.kt`, `ExerciseViewModel.kt`, `ui/components/Chips.kt`
- Test: `app/src/test/kotlin/com/simplegym/ui/exercise/ExerciseScreenTest.kt`

**Interfaces (Produces):**

```kotlin
data class SwapContext(val lowerBackProtection: Boolean, val unavailable: Set<String>, val useCounts: Map<String, Int>,
    val hurtFlagged: Set<String>, val stiffNeck: Boolean, val stiffLowerBack: Boolean)
fun orderSwaps(swaps: List<Exercise>, ctx: SwapContext): List<Exercise>
fun suggestedVariant(default: Exercise, swaps: List<Exercise>, ctx: SwapContext): Exercise
```

**`orderSwaps`:**
- **Drops** exercises in `unavailable`, and drops `backLoad == HIGH` when protection is on.
- **Sorts by:** not hurt-flagged first; then, if `stiffNeck`, `swapGroup` not in {`shrug`, `upright-row`} first; then `useCounts` descending; then original position.

**`suggestedVariant`:**
- **Stiff lower back:** if `stiffLowerBack` and the default's backLoad ∈ {MODERATE, HIGH}, returns the ordered swap with the lowest backLoad (ties go to the first).
- **Default hidden:** if the default is filtered out, returns the first ordered swap.
- **Otherwise:** returns the default.

- [ ] **Step 1: Write the failing tests.**
  - **`SwapsTest`:**
    - Protection on hides `"Barbell bent-over row"`.
    - An unavailable item is dropped.
    - A hurt-flagged item sorts last.
    - A stiff neck moves `"Upright row (cable)"` after the others.
    - A stiff lower back with default `"Barbell back squat"` (MODERATE) returns the `LOW` swap `"Seated leg press"`.
    - Use counts {DB press: 5, Chest press: 2} order DB press before Chest press.
  - **`ExerciseScreenTest`:**
    - The target card shows `"62.5"`, `"3 sets"`, `"8–12 reps"` and the reason text.
    - Four swap chips show weights or `"new"`.
    - Tapping a chip changes the target card to that exercise, in one tap.
    - `"More…"` opens a search field pre-filtered to the slot's swap group. Tapping `"All"` and typing `"preacher"` lists `"Preacher curl"`.
    - With lower-back protection on, `"All"` + `"bent-over"` lists `"Barbell bent-over row"` with a warning badge (§6.10: still findable in search), while the swap chips never include it.
    - **`WorkoutScreenTest` addition:** a slot whose default is unavailable at the session's club starts the timer (via the row's `"▶ Start timer"`) with the first ordered swap, not the default.
    - `"▶ Start timer"` navigates to `TimerRoute`.
    - `"Skip for now"` sets SKIPPED and pops back to Workout.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - The screen picks its initial variant with `resolveVariant` (below).
  - The search filters non-archived library exercises by case-insensitive substring. It's pre-filtered to the slot's swapGroup, with an "All" toggle. HIGH back-load results show a warning badge when protection is on, but are not hidden.
  - **Shared variant resolver:** add `suspend fun TargetService.resolveVariant(sessionId: Long, slotId: Long, today: LocalDate): String` to `TargetService`. It applies the rotation override (Task 16), then `suggestedVariant`. Both this screen's initial variant and `WorkoutViewModel`'s `"▶ Start timer"` use it. Update Task 8's row action to call it.
  - Use counts come from `LogDao`: a count of logs per exercise for this slot.
  - `SwapContext.unavailable` comes from the session's `clubId`, via `ClubUnavailableEntity`. `hurtFlagged` is empty until Task 18. Lower-back protection is read from settings.
  - Under the target card, show the fixed line `"Stop 1–2 reps short of failure."` (§6.2).
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "ui: exercise screen with swaps"`

### Task 10: Timer maths (engine)

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/TimerMath.kt`
- Test: `engine/src/test/kotlin/com/simplegym/engine/TimerMathTest.kt`

**Interfaces (Produces):**

```kotlin
enum class Phase { WORK, REST }
enum class Cue { COUNTDOWN, ROUND_START, FINISH }
data class EmomConfig(val rounds: Int, val roundSec: Int)                 // rounds 1..99, roundSec 15..600 step 15
data class IntervalConfig(val workSec: Int, val restSec: Int, val rounds: Int)
data class TimerState(val round: Int, val secondsLeft: Int, val phase: Phase, val finished: Boolean)
fun emomState(cfg: EmomConfig, elapsedMs: Long): TimerState
fun intervalState(cfg: IntervalConfig, elapsedMs: Long): TimerState
fun cueBetween(prev: TimerState?, cur: TimerState): Cue?
fun adjustRounds(cfg: EmomConfig, delta: Int): EmomConfig                 // clamps 1..99
fun adjustRoundSec(cfg: EmomConfig, deltaSteps: Int): EmomConfig         // ±15 s per step, clamps 15..600
fun rebaseEmom(old: EmomConfig, new: EmomConfig, elapsedMs: Long): Long   // new elapsed keeping current round & time-into-round
```

**`rebaseEmom`** supports mid-run adjustment.
- **Keeps the lifter's place:** with k = completed rounds and e = ms into the current round under `old`, it returns `k × new.roundSec × 1000 + min(e, new.roundSec × 1000 − 1000)`.
- **Can't remove rounds already started:** if `new.rounds < k + 1`, the rounds are treated as `k + 1`. Callers clamp `new` with `new.copy(rounds = max(new.rounds, k + 1))` first.

- **`secondsLeft`** is the ceiling of the remaining ms / 1000.
- **`cueBetween`** returns:
  - `COUNTDOWN` when `secondsLeft` changes to 3, 2 or 1 within the same round and phase;
  - `ROUND_START` when the round or phase changes and the timer is not finished (including the first state, where `prev == null`);
  - `FINISH` when `finished` becomes true.

- [ ] **Step 1: Write the failing tests.**
  - EMOM 3 × 120:
    - 0 ms → (1, 120, WORK, false)
    - 119_500 → (1, 1)
    - 120_000 → (2, 120)
    - 359_999 → (3, 1)
    - 360_000 → finished = true
  - Interval 30/10 × 3: 31_000 → (1, 9, REST); 40_000 → (2, 30, WORK); 120_000 → finished.
  - `cueBetween(null, s0) == ROUND_START`. (1,4) → (1,3) gives COUNTDOWN. (1,1) → (2,120) gives ROUND_START. → finished gives FINISH. (1,10) → (1,9) gives null.
  - `adjustRounds(3×120, −5)` gives 1 round. `adjustRoundSec(3×120, +1)` gives 135. `adjustRoundSec(1×15, −1)` stays at 15.
  - `rebaseEmom(3×120, 3×135, 150_000) == 165_000` (round 2, 30 s in, preserved).
  - `rebaseEmom(3×120, 3×60, 200_000) == 119_000` (round 2, 80 s in, clamped to 59 s into the 60 s round).
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "engine: timer maths"`

### Task 11: Timer foreground service and timer screen

**Files:**
- Create: `app/src/main/kotlin/com/simplegym/timer/TimerRunStore.kt`, `TimerService.kt`, `TimerController.kt`, `Cues.kt`
- Create: `app/src/main/kotlin/com/simplegym/ui/timer/TimerScreen.kt`, `TimerViewModel.kt`, `ui/home/BatteryCard.kt`
- Modify: `AndroidManifest.xml`. Add the permissions from Global Constraints and the `<service android:foregroundServiceType="specialUse">` with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Workout interval timer"/>`.
- Test: `app/src/test/kotlin/com/simplegym/timer/TimerRunStoreTest.kt`, `app/src/test/kotlin/com/simplegym/ui/timer/TimerScreenTest.kt`

**Interfaces (Produces):**
- **`TimerRun`** (`@Serializable`, primitive fields only, because the engine module has no serialization plugin):
  - **Kind and config:** `kind: String` ("EMOM" | "INTERVAL"), `rounds: Int`, `roundSec: Int`, `workSec: Int`, `restSec: Int`.
  - **Timing:** `startElapsedMs: Long`, `pausedAtElapsedMs: Long?`, `pausedTotalMs: Long`.
  - **Context:** `sessionId: Long?`, `slotId: Long?`, `exerciseId: String?`.
  - **Mappers:** `fun TimerRun.emom(): EmomConfig` and `fun TimerRun.interval(): IntervalConfig`.
- **`class TimerRunStore(prefs: SharedPreferences)`:**
  - `fun save(run: TimerRun)`, `fun load(): TimerRun?`, `fun clear()`
  - `fun elapsedMs(run: TimerRun, nowElapsedMs: Long): Long`, which excludes paused time.
- **`object TimerController`:**
  - `fun start(context, run: TimerRun)`, `fun pause(context)`, `fun resume(context)`, `fun stop(context)`
  - `fun adjust(context, rounds: Int, roundSec: Int)`: rewrites the running run's config, and sets `startElapsedMs` so that `elapsedMs` equals `rebaseEmom(old, new, currentElapsed)`. It saves the run.
  - `val state: StateFlow<TimerUiState>`, where `TimerUiState(running: Boolean, paused: Boolean, run: TimerRun?, timer: TimerState?)`
  - `fun isServiceRunning(): Boolean`
- **`data class KilledRun(val run: TimerRun, val timer: TimerState)`** and `fun detectKilledRun(store: TimerRunStore, isRunning: Boolean, nowElapsed: Long): KilledRun?` in `TimerRunStore.kt`.
- **`TimerService`:** started with `startForeground`. It holds a `PARTIAL_WAKE_LOCK` with a timeout of the run's total duration + 60 s. It ticks every 200 ms via a coroutine, computing state with `emomState` or `intervalState` from `SystemClock.elapsedRealtime()`.
  - On each `cueBetween` result it plays `Cues`:
    - `COUNTDOWN`: `ToneGenerator(STREAM_ALARM, 80)` `TONE_PROP_BEEP` + a 60 ms vibration.
    - `ROUND_START`: `TONE_PROP_ACK` + a 250 ms vibration.
    - `FINISH`: a long tone + a 600 ms vibration.
  - Its ongoing notification (channel `"timer"`, `VISIBILITY_PUBLIC`) shows `"Round x / y · m:ss"` with Pause/Resume and Stop actions.
  - On finish it clears the store and emits finished. The UI navigates to `LogRoute` **only** for EMOM runs with a non-null `slotId` and `exerciseId`. Interval (mobility) and standalone runs just return.
- **`TimerRoute` starts the run immediately on entry,** with no separate Start button, so tap 2 of the tap rule leaves the timer running. The ± adjusters on the timer screen call `TimerController.adjust`.
- **Defaults when starting from a slot:**
  - `rounds = if (target.type == NORMAL) slot.timerRounds ?: target.sets else target.sets`
  - `roundSec = if (target.type == FLUSH) 60 else slot.timerRoundSec ?: target.roundSec`
- **Every adjustment made from a NORMAL-type slot run** is written to `SlotEntity.timerRounds` / `timerRoundSec` immediately, not only at start.
- **Kill detection:** on app start, if `TimerRunStore.load() != null` and `!isServiceRunning()`:
  - If the recomputed state isn't finished, set `SettingsEntity.timerWasKilled = true` and show an inline `"Timer was interrupted — Resume"` banner on Home. Resume restarts the service with the *same* run, so time stays correct.
  - If it is finished, clear the store.
- **`BatteryCard`** is shown on Home while `!batteryCardDismissed || timerWasKilled`. Its text: `"Keep the timer running with the screen off: Settings → Battery → Unrestricted."` Its button opens `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the package. Dismissing it sets `batteryCardDismissed = true` and `timerWasKilled = false`.

- [ ] **Step 1: Write the failing tests.**
  - **`TimerRunStoreTest`:**
    - save/load round-trips.
    - `elapsedMs` with start 1_000, paused at 61_000 and now 100_000 → 60_000.
    - Start 1_000, paused total 20_000, not paused, now 141_000 → 120_000.
    - **Adjust test:** a run 3×120 started at elapsed 0, adjusted at now = 150_000 to 3×135 → `elapsedMs(run, 150_000) == 165_000`.
  - **Review Focus 2:** for a run saved at start 0 with no service running, at `nowElapsed = 130_000` the recomputed `emomState` is round 2, and the kill-detection function `fun detectKilledRun(store, isRunning: Boolean, nowElapsed: Long): KilledRun?` returns non-null.
  - **`TimerScreenTest`:**
    - Entering `TimerRoute` from a slot shows `"Round 1 / 3"` with the run already started.
    - `+` on rounds shows `"4"`, and `SlotEntity.timerRounds == 4` right away.
    - A DELOAD target with 2 sets starts with 2 rounds even when `timerRounds == 4`.
    - A FLUSH target starts with 60 s rounds.
    - A finished standalone run (no slotId) does not navigate to `LogRoute`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** Request `POST_NOTIFICATIONS` at the first timer start. If it's denied, the timer still runs with an in-app display only.
- [ ] **Step 4: Run them, then build.** `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit.** `git commit -m "timer: foreground service, screen, kill detection, battery card"`

### Task 12: Log screen and validation

**Files:**
- Create: `app/src/main/kotlin/com/simplegym/domain/LogInput.kt`, `ui/log/LogScreen.kt`, `ui/log/LogViewModel.kt`, `ui/components/NumberField.kt`
- Test: `app/src/test/kotlin/com/simplegym/domain/LogInputTest.kt`, `app/src/test/kotlin/com/simplegym/ui/log/LogScreenTest.kt`

**Interfaces (Produces):**

```kotlin
data class LogInput(val weightText: String, val repsText: String, val setsText: String, val feel: Feel, val hurt: Joint?, val note: String?)
sealed interface LogValidation { data class Ok(val weightKg: Double?, val reps: Int, val sets: Int) : LogValidation
                                 data class Error(val field: String, val message: String) : LogValidation }
fun validateLog(input: LogInput, exercise: Exercise): LogValidation
class LogService(db, sessionService) { suspend fun save(sessionId: Long, slotId: Long, exerciseId: String, ok: LogValidation.Ok,
    input: LogInput, target: Target, today: LocalDate): Long }
```

- **`save`** writes a `LogEntity`: type = `target.type` (FLUSH if the slot's flush is on), `rangeType = target.rangeType`, `imported = false`. It then sets the slot DONE in the same transaction.
- **Pre-fill:** weight = target weight (blank if null), reps = `target.repGoal ?: range.hi`, sets = target sets, feel = SOLID.
- **Assisted weight field:** labelled `"Assistance (kg)"`. It shows and accepts the absolute value and is stored negative.

- [ ] **Step 1: Write the failing tests.**
  - **`LogInputTest` (Review Focus 5):**
    - On an assisted exercise, `"70"` → Ok(−70.0), and `"-70"` → Ok(−70.0).
    - Reps `""` → Error("reps"), and reps `"0"` → Error("reps").
    - Sets `"0"` → Error("sets").
    - Weight `"abc"` → Error("weight").
    - On a bodyweight exercise, weight `"20"` → Ok(null, …).
    - On a loaded exercise, weight blank → Error("weight").
    - Weight `"62,5"` (comma decimal) → Ok(62.5).
  - **`LogScreenTest`:**
    - The fields are pre-filled with `62.5`, `12` and `3`, and Solid is selected.
    - `"This hurt"` reveals 7 joint chips.
    - Save writes one log with the chosen joint and returns to Workout with the next slot highlighted.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** `NumberField` uses `KeyboardType.Decimal` with ± stepper buttons; the step is the effective increment, or 1 for reps and sets.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "ui: log screen and validation"`

### Task 13: Starting-data import and onboarding (M1 release)

**Files:**
- Create: `app/src/main/kotlin/com/simplegym/io/StartingData.kt`, `ui/onboarding/OnboardingScreen.kt`, `ui/onboarding/OnboardingViewModel.kt`
- Create: `docs/examples/starting-data.example.json` (fictional values)
- Test: `app/src/test/kotlin/com/simplegym/io/StartingDataTest.kt`

**Interfaces (Produces):**

```kotlin
@Serializable data class StartingDataV1(val version: Int = 1, val profile: ProfileDto, val clubs: List<ClubDto> = emptyList(),
    val weights: List<WeightDto> = emptyList(), val hurt: List<HurtDto> = emptyList(), val customExercises: List<LibraryExercise> = emptyList())
@Serializable data class ProfileDto(val age: Int, val lowerBackProtection: Boolean)
@Serializable data class ClubDto(val name: String, val unavailable: List<String> = emptyList())
@Serializable data class WeightDto(val exerciseId: String, val weightKg: Double, val reps: Int? = null)
@Serializable data class HurtDto(val exerciseId: String, val joint: Joint)
class StartingDataImporter(db) { suspend fun import(json: String, today: LocalDate): ImportResult }
data class ImportResult(val imported: Int, val skippedUnknownIds: List<String>)
```

**Import:**
- **Each weight** becomes a `LogEntity(type = NORMAL, imported = true, date = today, feel = SOLID, sets = slot sets or 3, bestReps = reps ?: lo of the first slot using that exercise ?: 8)`.
- **Hurt entries** become a HELD log with `hurt` set, dated today.
- **Unknown exercise ids** are skipped and reported.
- **Custom exercises first:** `customExercises` are inserted with `isCustom = true` **before** resolving weights and hurt ids. Any whose id already exists is skipped.
  - **Test:** a weight referencing a custom exercise defined in the same file is imported, not skipped.
- **Version handling:** `version > 1` fails with the message `"This file was made by a newer version of Simple Gym."`

**Onboarding** (shown while `!onboarded`) offers two paths:
- **"Import starting data":** `ActivityResultContracts.OpenDocument` with `application/json` and `*/*`.
- **"Set up manually":** age (required, 16..99), a lower-back protection switch, and optional weights for the 20 starter defaults.

Both paths set `onboarded = true` and land on Home.

- [ ] **Step 1: Write the failing tests.**
  - Importing the example file gives the expected count and sets the profile age and protection.
  - An imported Bench press 60 with no reps → `targetFor` gives 60.0 with repGoal 9 (8–12 range, lo + 1) — the spec's "hold W, aim for lo + 1".
  - An unknown id `"nope"` is listed in `skippedUnknownIds`.
  - `version: 2` → failure with the exact message.
  - Clubs are created along with their unavailable rows.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "io: starting-data import and onboarding"`
- [ ] **Step 6: Main-session step (not the executor).**
  1. Build the lifter's real `personal/starting-data.json` from their Google Sheets (age, lower-back protection on, the two clubs, current working weights mapped to library ids, no deadlifts or RDLs).
  2. Create it as a private file in their Google Drive.
  3. Tell them which sheet values were ambiguous.
- [ ] **Step 7: M1 release.**
  1. `./gradlew :engine:test :app:testDebugUnitTest :app:assembleRelease` → BUILD SUCCESSFUL.
  2. `bash scripts/check-before-push.sh` → exit 0.
  3. `git push`.
  4. Hand the signed APK to the lifter, via adb install over USB or a private Drive upload, and get feedback before M2.

---

## Milestone 2: Coaching depth

### Task 14: Age rules, rep ranges, e1RM conversion

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/AgeRules.kt`, `Ranges.kt`
- Test: `engine/src/test/kotlin/com/simplegym/engine/AgeRulesTest.kt`, `RangesTest.kt`

**Interfaces (Produces):**

```kotlin
data class AgeRules(val buildRounds: Int, val alternateRanges: Boolean, val maxStepsPerSession: Int)
fun ageRules(age: Int): AgeRules
fun alternationApplies(base: RepRange, exercise: Exercise): Boolean      // false if base.hi >= 15 || assisted || bodyweight
fun rangeTypeForBlock(blockIndex: Int, alternate: Boolean): RangeType    // index 0 = BASE; odd = HIGH_REP when alternate
fun effectiveRange(base: RepRange, type: RangeType, exercise: Exercise): RepRange  // HIGH_REP: lo+4, min(hi+4, 20)
fun convertWeight(weightKg: Double, fromReps: Int, toReps: Int, stepKg: Double): Double  // Epley e1RM = w(1+r/30), round to nearest step
```

- [ ] **Step 1: Write the failing tests.**
  - **`ageRules`:** 39 → (5, false, 2); 40 → (3, true, 2); 54 → (3, true, 2); 55 → (2, true, 1).
  - **`effectiveRange`:** 8–12 HIGH_REP → 12–16; 12–15 HIGH_REP → 12–15 (excluded).
  - **Exclusions:** an assisted 6–10 stays 6–10.
  - **`rangeTypeForBlock`:** (0, true) → BASE, (1, true) → HIGH_REP, (2, true) → BASE, (1, false) → BASE.
  - **`convertWeight`:** (60.0, 12, 16, 2.5) → 55.0; (55.0, 16, 12, 2.5) → 60.0.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "engine: age rules and rep ranges"`

### Task 15: Blocks, rounds, deload, home header

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/Blocks.kt`, `app/src/main/kotlin/com/simplegym/domain/BlockService.kt`
- Test: `engine/src/test/kotlin/com/simplegym/engine/BlocksTest.kt`, `app/src/test/kotlin/com/simplegym/domain/BlockServiceTest.kt`

**Interfaces (Produces):**

```kotlin
data class DaySessionSummary(val dayId: Long, val date: LocalDate, val slotsLogged: Int, val slotsTotal: Int, val flushOnly: Boolean)
data class BlockState(val blockIndex: Int, val roundInBlock: Int, val roundsPerBlock: Int, val isDeloadRound: Boolean,
    val doneThisRound: Set<Long>)
fun countsAsDone(s: DaySessionSummary): Boolean        // slotsLogged * 2 >= slotsTotal && !flushOnly && slotsLogged > 0
fun blockState(activeDayIds: Set<Long>, sessions: List<DaySessionSummary>, buildRounds: Int, today: LocalDate): BlockState
fun headerText(s: BlockState): String                  // "Block 2 · Round 2 of 4"; deload round: "Block 2 · Deload round"
class BlockService(db) { suspend fun current(today: LocalDate): BlockState }   // builds summaries from DB; slotsTotal from SessionEntity
```

**`blockState`:** fold chronologically.
- **Rounds:** a round completes when `doneThisRound ⊇ activeDayIds`, with `activeDayIds` non-empty. `roundsPerBlock = buildRounds + 1`. The last round in a block is the deload round.
- **Deload auto-close:** a deload round whose start date (the date the previous round completed) is more than 14 days before the next session date, or before `today`, closes automatically and the next block starts.
- **Empty `activeDayIds`:** gives block 0, round 1.
- **Display:** `blockIndex` is 0-based in data and shown 1-based.

- [ ] **Step 1: Write the failing tests.** Days A=1, B=2, C=3, buildRounds 3.
  - A, B, C (all full) → round 2, `doneThisRound` {}.
  - A, A, B → round 1, `doneThisRound` {1, 2}.
  - A with 2/5 logged → not done; 3/5 → done; 2/4 → done.
  - Flush-only → not done.
  - 9 qualifying sessions → `isDeloadRound` true, round 4.
  - 12 → block 1, round 1.
  - A deload round starting on D with no sessions, and `today = D + 15` → block 1, round 1, not deload.
  - **Review Focus 3:** delete day C after A and B are done → active {1, 2} completes round 1 immediately. Next state is round 2.
  - `headerText` gives `"Block 2 · Round 2 of 4"` for (1, 2, 4, false) and `"Block 1 · Deload round"` for (0, 4, 4, true).
  - **`BlockServiceTest`:** after a slot is deleted from a day, a session started earlier still uses its stored `slotsTotal`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** Show `headerText` on Home.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "engine: blocks, rounds and deload"`

### Task 16: Plateau detection and block rotation

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/Plateau.kt`
- Test: `engine/src/test/kotlin/com/simplegym/engine/PlateauTest.kt`
- Modify: `ExerciseScreen.kt`/`ExerciseViewModel.kt` (the rotation banner), `PlanDao` (rotation writes)

**Interfaces:**
- Consumes: `BlockService.current(today).blockIndex` (Task 15), used as `currentBlockIndex`.

**Produces:**
- `const val PLATEAU_LIMIT = 3`
- `fun plateauCount(baselines: List<LogRecord>): Int`. The input is chronological and already filtered to one exercise and one rangeType. Walking pairs:
  - weight up, or the same weight with reps up → 0;
  - weight down → 0;
  - the same weight with reps not up → +1.
- `suspend fun PlanDao.setRotation(slotId: Long, exerciseId: String?, blockIndex: Int?)`

- [ ] **Step 1: Write the failing tests.**
  - [60×10, 60×10, 60×10, 60×10] → 3.
  - [60×10, 60×11] → 0.
  - [60×10, 62.5×8, 62.5×8] → 1.
  - [60×10 ×4, 52.5×12] → 0.
  - Empty or single → 0.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **Exercise screen:** when the current variant's plateau count is ≥ `PLATEAU_LIMIT`, show the inline banner `"Rotate to another variation for this block?"` with swap chips. Tapping one calls `setRotation(slotId, id, currentBlockIndex)`.
  - **Slot default resolution:** when `rotationBlockIndex == currentBlockIndex`, use `rotationExerciseId`.
  - **New block:** on the first session of a new block, show the one-tap chip `"Keep <variant> as default?"`. Yes sets `defaultExerciseId`. Either answer clears the rotation.
  - Add a Robolectric test: with a rotation set for block 0, the slot shows the rotated variant.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "engine+ui: plateau detection and block rotation"`

### Task 17: Layoff

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/Layoff.kt`
- Test: `engine/src/test/kotlin/com/simplegym/engine/LayoffTest.kt`

**Interfaces (Produces):** `fun layoffFraction(daysSinceMovementTrained: Long?): Double?`. It returns null for null input or < 10 days; 0.10 for 10..20; 0.20 for 21..42; 0.30 for > 42.

- [ ] **Step 1: Write the failing tests.** 9 → null, 10 → 0.10, 20 → 0.10, 21 → 0.20, 42 → 0.20, 43 → 0.30, null → null.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "engine: layoff rule"`

### Task 18: Hurt flags, joint caution, inline banner

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/Hurt.kt`, `app/src/main/kotlin/com/simplegym/ui/components/HurtBadge.kt`
- Test: `engine/src/test/kotlin/com/simplegym/engine/HurtTest.kt`
- Modify: `ExerciseScreen.kt` (banner + badges), `Chips.kt` (badges), `WorkoutScreen.kt` (badges)

**Interfaces (Produces):**

```kotlin
data class HurtEvent(val exerciseId: String, val joint: Joint, val date: LocalDate)
fun activeHurts(history: List<LogRecord>, clearedOn: LocalDate?): Map<Joint, Int>   // variant's own logs, chronological
fun jointCaution(exercise: Exercise, otherHurts: List<HurtEvent>, ownLogs: List<LogRecord>, today: LocalDate): Joint?
fun jointFriendlySwap(joint: Joint, swaps: List<Exercise>, group: List<Exercise>): Exercise?
```

**`activeHurts`:**
- Considers only logs dated after `clearedOn`.
- Each hurt log increments its joint's count and resets the pain-free streak.
- Each pain-free log increments the streak. At 3 pain-free logs, all counts clear.

**`jointCaution`:**
- **Triggers on:** a hurt event on another exercise, on a joint in `exercise.joints`, with `today − date ≤ 13` days.
- **Lifted by:** the exercise having ≥ 2 pain-free own logs dated after that event.
- **Returns:** the joint, or null.

**`jointFriendlySwap`:**
- The first swap not stressing that joint.
- Otherwise, the group member not stressing it with the fewest joints.
- Otherwise null.

- [ ] **Step 1: Write the failing tests.**
  - **`activeHurts`:**
    - [hurt SHOULDER, ok, ok] → {SHOULDER: 1}.
    - [hurt, ok, ok, ok] → {}.
    - [hurt, hurt] → {SHOULDER: 2}.
    - A clear date after both hurts → {}.
  - **`jointCaution`:**
    - A Bench press SHOULDER hurt on day 0; for Machine shoulder press (joints ⊇ SHOULDER), day 13 → SHOULDER and day 14 → null.
    - With 2 pain-free own logs after day 0 → null.
  - **`jointFriendlySwap`** (synthetic `Exercise` fixtures with hand-set joint sets, not library-derived): SHOULDER with swaps [A {SHOULDER, ELBOW}, B {SHOULDER, WRIST}] and group [A, B, C {ELBOW}, D {ELBOW, WRIST}] → C. All containing SHOULDER → null.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **`HurtBadge`:** `"⚠ Shoulder · 2×"` in amber, shown on chips, workout rows and the exercise header.
  - **Exercise screen inline banner:** `"This hurt your <joint> last <n> times. Try <swap> instead?"`, with the swap chip. The softer grey note: `"<Joint> flagged recently"`.
  - **Settings action:** `"Clear hurt flag"` sets `hurtClearedOn = today`. Its UI ships in Task 24; add `suspend fun LibraryDao.clearHurt(exerciseId: String, on: LocalDate)` now.
  - **`ExerciseViewModel` feeds `SwapContext.hurtFlagged`:** the ids with non-empty `activeHurts`. Both the `swaps` **and** the `group` arguments passed to `jointFriendlySwap` are first run through `orderSwaps` with the same `SwapContext`, plus a non-archived filter. That way lower-back protection, club availability and archiving apply to the fallback too (§6.10).
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "engine+ui: hurt flags and joint caution"`

### Task 19: Coach facade: modifiers combined, flush, TargetService v2

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/Modifiers.kt`, `Coach.kt`
- Test: `engine/src/test/kotlin/com/simplegym/engine/ModifiersTest.kt`, `CoachTest.kt`
- Modify: `app/src/main/kotlin/com/simplegym/domain/TargetService.kt` (assemble `CoachInput`; signature unchanged), `ExerciseScreen.kt` (the "Flush instead" toggle)

**Interfaces (Produces):**

```kotlin
data class Candidate(val weightKg: Double?, val sets: Int, val type: SessionType, val reason: String)
fun mostConservative(candidates: List<Candidate>): Candidate   // lowest weight (null weights compare equal), then fewest sets, then first
data class SlotPlan(val sets: Int, val baseRange: RepRange, val roundSec: Int)
data class CoachInput(val exercise: Exercise, val slot: SlotPlan, val history: List<LogRecord>, val hurtClearedOn: LocalDate?,
    val movementLastTrained: LocalDate?, val otherHurts: List<HurtEvent>, val sameEquipmentHint: WeightHint?,
    val block: BlockState, val rules: AgeRules, val stiffMuscle: Boolean, val flush: Boolean, val today: LocalDate)
fun coachTarget(input: CoachInput): Target
```

**`coachTarget` algorithm** (the order matters; implement exactly):
1. **Range type:** `rt = if (rules.alternateRanges && alternationApplies(slot.baseRange, ex)) rangeTypeForBlock(block.blockIndex, true) else BASE`, and `range = effectiveRange(...)`.
2. **Baseline:** the **most recent** `isBaseline` log of either range type.
   - **Same range type as `rt`:** use it as is.
   - **Different range type:** synthesise W = `convertWeight(that.w, that.reps, range.hi, step)` with R = `range.lo` and SOLID. Converting to the *top* of the new range is the conservative choice, and it's my call. This is the spec's "converted back from the latest higher-rep logs".
   - **Plateau input:** `plateauCount` (step 5) still uses only the logs of type `rt`.
3. **No baseline at all:** return the cold-start target from Task 6, with `hint`.
4. **Flush:** if `flush`, return `Target(reduceBy(W, 0.55, step, assisted), if (slot.sets >= 3) 3 else 2, RepRange(15, 20), null, FLUSH, "Flush: light weight, high reps to get blood into a stiff muscle.")`, i.e. about 45% of W.
5. **Normal candidate:** if `plateauCount(baselines of rt) >= PLATEAU_LIMIT`, use `reduceBy(W, 0.10)` with the reason `"Plateau: 3 sessions without progress, so dropping 10% to build back up."` Otherwise use `progress(base, range, ex, rules.maxStepsPerSession)`. Type NORMAL.
6. **Modifier candidates:**
   - **Deload round:** `(reduceBy(W, 0.10), max(2, slot.sets − 1), DELOAD, "Deload round: lighter so your joints catch up.")`
   - **Stiff muscle:** `(W, sets, HELD, "Stiff today: holding at <W>kg with a longer warm-up.")`
   - **Layoff:** if `layoffFraction(days(movementLastTrained, today)) = f`, `(reduceBy(W, f), sets, RETURN, "Back after <d> days: easing in at −<f%>.")`
   - **Active hurt:** if `activeHurts` is non-empty, `(stepDown(W), sets, HELD, "This hurt last time: one step lighter.")`
   - **Joint caution:** if `jointCaution` returns a joint j, `(W, sets, HELD, "<j> flagged recently: holding.")`
7. **Winner:** `w = mostConservative([normal] + modifiers)`. Return `Target(w.weightKg, w.sets, range, if (w.type == NORMAL) normal.repGoal else null, w.type, w.reason, rangeType = rt, roundSec = slot.roundSec)`. The flush target in step 4 uses `roundSec = 60` and `rangeType = rt`.

**Bodyweight / null-W rule:** when W is null, every weight in steps 4–6 stays null.
- **Deload:** changes only sets.
- **Flush:** sets 2–3 and reps 15–20.
- **Plateau, stiff, return, hurt and joint caution:** become HELD with `repGoal = base.bestReps`, i.e. no rep increase.
- **Winner:** `mostConservative` then decides on sets, and HELD beats NORMAL at equal sets. Implement that as an explicit tie-break: non-NORMAL wins ties.

- [ ] **Step 1: Write the failing tests.**
  - **`ModifiersTest`:** [normal 62.5×3, deload 52.5×2, return 55×3, hurt 57.5×3] → deload 52.5×2. This is not a product of the modifiers. Two equal weights → fewer sets wins. Assisted [−67.5, −77.5] → −77.5.
  - **`CoachTest`** (Bench press, 8–12, 3 sets, step 2.5, baseline 60×12 SOLID, age 50 rules):

| Test name | Input | Expected |
|---|---|---|
| `plainProgression` | — | 62.5, NORMAL |
| `deloadRound` | deload round | 52.5 ×2, DELOAD |
| `stiffHolds` | stiff | 60.0, HELD |
| `layoff21Days` | movementLastTrained 21 days ago | 47.5, RETURN (60 × 0.8 = 48 → floored to 47.5) |
| `combinedPicksSingleLowest` | deload + stiff + 21-day layoff | 47.5 ×3, RETURN, since RETURN 47.5 is below DELOAD 52.5 |
| `flush` | flush | 25.0 ×3, 15–20, FLUSH (60 × 0.45 = 27 → 25) |
| `highRepBlockConverts` | blockIndex 1, only BASE baseline 60×12 | range 12–16, W = `convertWeight(60, 12, 16, 2.5)` = 55.0 (84 / 1.5333 = 54.78), R = 12 → 55.0, repGoal 13, NORMAL, rangeType HIGH_REP |
| `plateauResets` | 4 identical baselines 60×10 | 52.5, reason starts `"Plateau"` |
| `coldStart` | no history | weight null, hint passed through |
| `assistedDeload` | −70 assisted, deload | −77.5 |
| `hurtStepsDown` | last log hurt SHOULDER (W from earlier baseline 60) | 57.5, HELD |
| `baseHighBaseUsesLatest` | blockIndex 2 (BASE), baselines: BASE 57.5×12 (old), then HIGH_REP 55×16 (newer) | W = `convertWeight(55, 16, 12, 2.5)` = 60.0 (84.33 / 1.4 = 60.24), R = 8 → 60.0, repGoal 9, rangeType BASE. This shows the newer high-rep log wins over the stale 57.5 |
| `bodyweightDeload` | Chin-up bodyweight, 4 sets 6–10, baseline null×8, deload | weight null, 3 sets, DELOAD |
| `bodyweightFlush` | Chin-up bodyweight, flush | weight null, 3 sets, 15–20, FLUSH, roundSec 60 |
| `targetCarriesRangeType` | blockIndex 1, alternation on | rangeType HIGH_REP |

- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement** `Modifiers.kt` and `Coach.kt`, then rewrite the `TargetService.targetFor` body to build a `CoachInput`:
  - **`movementLastTrained`:** the max log date over all exercises in the same `swapGroup`, imported logs included.
  - **`stiffMuscle`:** whether today's `CheckInEntity` contains a chip whose `muscles` include `exercise.muscle`.
  - **`flush`:** from `SessionSlotEntity.flush`.
  - **`block`:** from `BlockService`.
  - **`rules`:** `ageRules(settings.age ?: 45)`.
  - **Exercise screen:** add the inline `"Flush instead"` switch, shown only when `stiffMuscle`, bound to `SessionService.setFlush`.
  - Keep the Task 6 tests passing.
- [ ] **Step 4: Run them.** `./gradlew :engine:test :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "engine: coach facade with combined modifiers and flush"`

### Task 20: Stiffness check-in, day suggestion, recovery learning, flush finisher (M2 release)

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/Suggestion.kt`, `app/src/main/kotlin/com/simplegym/domain/CheckInService.kt`
- Test: `engine/src/test/kotlin/com/simplegym/engine/SuggestionTest.kt`, `app/src/test/kotlin/com/simplegym/ui/home/CheckInTest.kt`
- Modify: `HomeScreen.kt`/`HomeViewModel.kt`, `WorkoutScreen.kt` (the flush finisher row), `ExerciseViewModel.kt` (stiff neck/lower back → `SwapContext`)

**Interfaces (Produces):**

```kotlin
data class DayCard(val dayId: Long, val chips: Set<Chip>, val lastDone: LocalDate?)
data class DaySuggestion(val dayId: Long?, val blocked: Set<Long>, val flushPrompt: Boolean)
data class CheckIn(val date: LocalDate, val stiff: Set<Chip>)
data class Trained(val date: LocalDate, val chips: Set<Chip>)
fun suggestDay(days: List<DayCard>, stiff: Set<Chip>, doneThisRound: Set<Long>, typicalStiffDays: Map<Chip, Int>, today: LocalDate): DaySuggestion
fun typicalStiffDays(checkIns: List<CheckIn>, trained: List<Trained>): Map<Chip, Int>
class CheckInService(db) { suspend fun setStiff(today: LocalDate, chips: Set<Chip>); fun today(today: LocalDate): Flow<Set<Chip>> }
```

**`suggestDay`:**
- **Blocked days:** days whose chips intersect `stiff` minus {LOWER_BACK, NECK}.
- **Among unblocked days:** the longest since `lastDone` (null = longest). Ties go to a day not in `doneThisRound`, then to the lowest summed `typicalStiffDays` over its chips.
- **All blocked:** the oldest `lastDone`, with `flushPrompt = true`.

**`typicalStiffDays`:**
- **One sample** per (training date d, chip c): the last consecutive check-in date after d that has c tapped, minus d, stopping at the first check-in without c. A training with no check-in after it that has c gives no sample.
- **Result:** the median, rounded up, for chips with ≥ 2 samples.

- [ ] **Step 1: Write the failing tests.**
  - **`suggestDay`:**
    - Legs stiff → the Legs day is blocked, and the suggestion is the unblocked day with the oldest `lastDone`.
    - All stiff → the oldest day, with `flushPrompt` true.
    - LOWER_BACK stiff blocks nothing.
    - Tie on `lastDone` → the day not done this round.
  - **`typicalStiffDays`:** LEGS trained on d0 and d10; check-ins d2 {LEGS}, d3 {}, d13 {LEGS}, d14 {} → {LEGS: 3}. A single sample → absent.
  - **`CheckInTest`:**
    - Tapping the "Legs" chip persists today's check-in and moves "Suggested today" off Legs / Calves.
    - The Legs card shows `"Stiff · flush option"`.
    - With `typicalStiffDays` {LEGS: 3}, the Legs card shows `"Legs: usually loose after 3 days"`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **Home:** a chip row with 9 chips: `Back, Biceps, Chest, Triceps, Shoulders, Legs, Calves, Lower back, Neck`. "Suggested today" is highlighted, and the Start button stays 1 tap.
  - **Workout screen flush finisher:** when today has stiff chips not trained by this day, show an optional row `"+ Flush finisher: <chip>"`.
    - **Tapping it** inserts a `SlotEntity(sessionOnlyFor = sessionId, sets = 2, lo = 15, hi = 20, roundSec = 60, defaultExerciseId = the chip's most-logged exercise, or the first library exercise with that muscle)` plus a `SessionSlotEntity(flush = true)`. It then behaves like any slot: targets come through `targetFor`, which is FLUSH because `flush = true`.
    - **Test:** adding a finisher doesn't change `slotsTotal` or the round state, and `PlanDao` day queries don't return it.
  - **Exercise screen:** feed `stiffNeck` and `stiffLowerBack` into `SwapContext`.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "engine+ui: stiffness check-in and day suggestion"`
- [ ] **Step 6: M2 release.** Run the same commands as Task 13 Step 7. Hand over the APK and collect feedback.

---

## Milestone 3: Extras

### Task 21: Mobility add-on and standalone timer

**Files:**
- Create: `engine/src/main/kotlin/com/simplegym/engine/Mobility.kt`, `app/src/main/kotlin/com/simplegym/ui/mobility/MobilityScreen.kt`, `MobilityViewModel.kt`, `ui/timer/StandaloneTimerScreen.kt`
- Test: `engine/src/test/kotlin/com/simplegym/engine/MobilityTest.kt`, `app/src/test/kotlin/com/simplegym/ui/mobility/MobilityScreenTest.kt`

**Interfaces (Produces):**

```kotlin
enum class MobilityArea(val swapGroups: List<String>) { NECK(listOf("mobility-neck")),
    LOWER_BACK(listOf("mobility-lowerback", "stability-lowerback")), HIPS(listOf("mobility-hips", "hip-dynamic")),
    SHOULDERS(listOf("mobility-shoulders")), CORE(listOf("core-stability", "core-leg-raise")) }
data class Routine(val moves: List<Exercise>, val workSec: Int, val restSec: Int)
fun buildRoutine(areas: List<MobilityArea>, library: List<Exercise>, workSec: Int = 30, restSec: Int = 10): Routine
fun neglectedOrder(lastDone: Map<MobilityArea, LocalDate>, today: LocalDate): List<MobilityArea>
```

**`buildRoutine`:**
- **Move order:** the selected areas' library moves in area order, then library order.
- **Length:** the move list is repeated until total time (moves × (work + rest)) ≥ 480 s, or 3 passes, whichever comes first.
- **Cap at 15 moves (600 s):** if a single pass is over 600 s, take moves round-robin across the selected areas until there are 15, so the routine stays about 8–10 minutes and every area is represented.

**`neglectedOrder`:** never-done areas first, then by oldest date.

- [ ] **Step 1: Write the failing tests.**
  - **`buildRoutine`:**
    - [NECK] → 9 moves (3 × 3 passes).
    - All 5 areas → exactly 15 moves, with every area present.
    - [LOWER_BACK, NECK] → lower-back moves first.
  - **`neglectedOrder`:** never-done areas first, then the oldest.
  - **`MobilityScreenTest`:**
    - Lower back tapped stiff today → LOWER_BACK is pre-selected and listed first.
    - `"Start"` launches the interval timer (`TimerRun.kind == INTERVAL`).
    - Finishing writes one `MobilityDoneEntity` per area.
    - The neglected-areas hint shows the top one: `"Not done lately: Shoulders"`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Workout's `"Core / mobility add-on"` row opens `Mobility(sessionId)`.
  - Home's Timer entry opens `StandaloneTimer`, with EMOM and Interval tabs that use `TimerController` and no logging.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "ui: mobility add-on and standalone timer"`

### Task 22: Progress chart

**Files:**
- Create: `app/src/main/kotlin/com/simplegym/ui/progress/ChartData.kt`, `WeightChart.kt`, `ProgressScreen.kt`, `ProgressViewModel.kt`
- Test: `app/src/test/kotlin/com/simplegym/ui/progress/ChartDataTest.kt`

**Interfaces (Produces):**
- `data class ChartPoint(val date: LocalDate, val weightKg: Double, val reps: Int, val faded: Boolean, val hurt: Boolean)`
- `fun chartPoints(logs: List<LogRecord>): List<ChartPoint>`:
  - **Skips** logs with a null weight.
  - **`faded`** when the type is DELOAD, FLUSH or RETURN.
  - **`hurt`** when `hurt != null`.
  - **Ordering:** sorted by date.
- `@Composable fun WeightChart(points: List<ChartPoint>, modifier: Modifier)`: a Canvas line through the points. Raw values are plotted, so assisted −70 → −60 rises. Points are labelled with reps; faded points use 35% alpha; hurt points get an amber marker.

- [ ] **Step 1: Write the failing tests.**
  - Mixed types → the correct `faded` flags.
  - Assisted [−70, −65, −60] → y-values increase.
  - A null-weight log is skipped.
  - A Robolectric test: the Progress screen lists exercises with history and opens a chart titled with the exercise name.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** The exercise picker lists only exercises with ≥ 1 log, most recent first.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "ui: progress chart"`

### Task 23: Plans editor and custom exercises

**Files:**
- Create: `app/src/main/kotlin/com/simplegym/domain/PlanEditor.kt`, `ui/plans/PlansScreen.kt`, `DayEditorScreen.kt`, `SlotEditorScreen.kt`, `ExerciseEditorScreen.kt`, `PlansViewModel.kt`
- Test: `app/src/test/kotlin/com/simplegym/domain/PlanEditorTest.kt`

**Interfaces (Produces):** `class PlanEditor(db)` with:
- **Days:** `addDay(name, chips)`, `renameDay`, `setDayChips(dayId, chips)`, `reorderDays(ids)`, `archiveDay(id)`, `deleteDay(id)`. Delete with sessions → archive.
- **`fun proposeChips(slotMuscles: List<Muscle>): Set<Chip>`:** every chip whose `muscles` intersect the slots' primary muscles. It's used when a day is created with empty chips and offered as a "Suggest" button in the day editor.
  - **Test:** [CHEST, TRICEPS, SHOULDERS] → {CHEST, TRICEPS, SHOULDERS}; [QUADS, GLUTES] → {LEGS}.
- **Slots:** `addSlot(dayId, exerciseId, sets, lo, hi, roundSec)`, `updateSlot(...)`, `deleteSlot(id)`, `reorderSlots(dayId, ids)`, `setSwaps(slotId, ids)`.
- **Exercises:** `addCustomExercise(LibraryExercise)`, `deleteExercise(id)`. Delete with logs or plan references → `archived = true`.
- **Validation:** `lo ≥ 1`, `hi ≥ lo`, `sets 1..10`, and `roundSec` a multiple of 15 in 15..600. Violations throw `IllegalArgumentException` with a field name.

- [ ] **Step 1: Write the failing tests.**
  - `addDay` + `addSlot` → the day shows up in Home's list.
  - `updateSlot(lo = 10, hi = 8)` throws.
  - **Review Focus 4:** `deleteExercise` on one with logs → archived, its logs intact, absent from `orderSwaps` input and search, still in `chartPoints`.
  - `deleteExercise` on an unused custom exercise → row deleted.
  - **Review Focus 3:** `deleteSlot` mid-session keeps its logs, with `slotId` null.
  - `deleteDay` with sessions → archived, and excluded from `activeDayIds`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **Screens:** Plans list → day editor (slots with drag-handle reorder via up/down buttons) → slot editor (default exercise via library search, sets, lo, hi, round time, swap list add/remove/reorder) → exercise editor for custom exercises.
  - **Confirmations:** destructive actions use a confirm dialog, which the tap rule allows.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "ui: plans editor"`

### Task 24: Settings, clubs, increments, hurt clearing

**Files:**
- Create: `app/src/main/kotlin/com/simplegym/ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, `ClubsScreen.kt`
- Modify: `Chips.kt` (long-press → `"Not at this club"`), `HomeScreen.kt` (club switcher)
- Test: `app/src/test/kotlin/com/simplegym/ui/settings/SettingsTest.kt`

**Interfaces:**
- Consumes: `ClubDao`, `SettingsDao`, `effectiveIncrement` (Task 6), `LibraryDao.clearHurt` (Task 18).
- Produces: `suspend fun ClubDao.setUnavailable(clubId, exerciseId, unavailable: Boolean)` and `suspend fun SettingsDao.setCurrentClub(id: Long?)`.

- [ ] **Step 1: Write the failing tests.**
  - After adding `"Barbell bent-over row"` to the Chest-supported row slot's swap list, toggling lower-back protection on removes it from that slot's chips. It stays findable in search with a warning badge.
  - Long-pressing a chip → `"Not at this club"` → the chip disappears for that club and reappears after switching club.
  - The Home club switcher is shown only when ≥ 1 club exists.
  - Setting the Dumbbell increment to 2.0 → the next target for a dumbbell exercise steps by 2.
  - `"Clear hurt flag"` removes the badge.
  - Age must be 16..99.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** Settings sections:
  - Profile (age, lower-back protection)
  - Clubs
  - Increments (per equipment type, plus a per-exercise override from the exercise editor)
  - Hurt flags (list + clear)
  - Data (Task 25)
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "ui: settings and clubs"`

### Task 25: Backup and restore

**Files:**
- Create: `app/src/main/kotlin/com/simplegym/io/Backup.kt`
- Modify: `SettingsScreen.kt` (Data section)
- Test: `app/src/test/kotlin/com/simplegym/io/BackupTest.kt`

**Interfaces (Produces):**
- `@Serializable data class BackupV1(val version: Int = 1, val exportedOn: String, ...)`, with one list per entity from Task 5.
- `class BackupService(db)`:
  - `suspend fun export(): String`
  - `suspend fun restore(json: String): RestoreResult`, which replaces all tables in one transaction.
- **Version handling:** restoring `version > 1` fails with `"This backup was made by a newer version of Simple Gym."` and leaves the data untouched.
- **UI:**
  - **"Save backup":** `CreateDocument("application/json")` with the default name `simple-gym-<yyyy-MM-dd>.gymbackup`.
  - **"Restore backup":** `OpenDocument` behind a destructive confirm.
  - **"Import starting data":** reuses Task 13.

- [ ] **Step 1: Write the failing tests.**
  - Seed + logs + a club + a check-in → export → wipe → restore → every table count and a sample log are equal.
  - `version: 2` → failure, with the data unchanged.
  - Malformed JSON → failure, with the data unchanged.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "io: backup and restore"`

### Task 26: README, tap-rule check, final release

**Files:**
- Create: `README.md` (generic: what the app does, how to build, that signing uses a git-ignored `keystore.properties`; no personal data)
- Test: `app/src/test/kotlin/com/simplegym/ui/TapBudgetTest.kt`

- [ ] **Step 1: Write the failing test `TapBudgetTest`** (Robolectric, seeded, onboarded, one stiff chip set). This pins §5.12.
  - From a freshly launched `MainActivity`, exactly 2 clicks reach a running timer: `"Start <suggested day>"`, then `"▶ Start timer"`. Assert that `TimerController.state` is running and that no dialog node exists.
  - From the Exercise screen, one click on a swap chip changes the target card.
- [ ] **Step 2: Run it.** Expected: PASS if Tasks 7–20 were built as specified. If it fails, fix the screen that adds the extra tap, not the test.
- [ ] **Step 3: Run the full verification.**
  1. `./gradlew :engine:test :app:testDebugUnitTest :app:assembleRelease` → BUILD SUCCESSFUL.
  2. `bash scripts/check-before-push.sh` → exit 0.
  3. `git status` → clean apart from ignored files.
- [ ] **Step 4: Commit and push.** `git commit -m "docs: readme; test: tap budget"`, then `git push`.
- [ ] **Step 5: Hand the final signed APK to the lifter.** Remind them to save a backup to Drive after their first week.
