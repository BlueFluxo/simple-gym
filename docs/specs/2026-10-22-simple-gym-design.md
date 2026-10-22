# Simple Gym — Design

Date: 2026-10-22
Status: draft for approval

## 1. Intent

An Android app for a lifter who trains at a busy shared commercial gym. They want to walk in, pick today's workout, pick an exercise, and be told **what weight to lift for how many reps**. The app should learn from how they actually lift and keep them progressing without overtraining.

It's aimed at lifters over 40. For them, the common problems are:

- stalling on weights;
- sore joints;
- muscle stiffness that takes longer to clear than it used to.

It replaces a spreadsheet log and a separate EMOM timer app. In the spreadsheet, each exercise gets one line with reps and weight, plus "Alt" columns for swaps when equipment is taken.

**Why it matters:**

- **Swaps.** The gym is shared, so equipment is often busy. Swaps and out-of-order exercises are part of a normal session, not exceptions.
- **Working weight per variant.** Each variant (bench press, chest press machine, dumbbell press, …) has its own working weight. The app must remember each one separately.
- **The coaching logic.** Progression, deloads, stiffness handling and joint warnings are what separate this from a plain logbook.

## 2. Goals, non-goals, anti-goals

### Goals

1. **Tells you what to lift.** Pick a workout and an exercise, and see the target weight, sets, rep range and the reason for it ("+2.5kg: you hit 12 easy last time").
2. **Fast swaps.** One-tap swap to an alternative for the same movement. Each alternative shows its own remembered working weight.
3. **Any order.** Do the day's exercises in any order. Skipped exercises stay marked so you come back to them.
4. **Lightweight logging.** Per exercise per session: working weight, best reps, number of sets and a feel rating. No per-set logging.
5. **Built-in timer.** An EMOM timer started from the exercise, adjustable for rounds and round time, and reliable with the screen off.
6. **Coaching that learns.** Progression, plateau handling, deload blocks, stiffness-aware recommendations, flush (active-recovery) sessions, layoff handling, joint-pain warnings and a lower-back protection setting.
7. **Core/mobility add-on.** A short optional routine (neck, lower back, hips, shoulders, core) on an interval timer.
8. **Progress view.** Working weight over time for each exercise variant.
9. **Editable plans.** Create and edit workout days, exercises, sets, rep ranges, round times and swap lists.

### Non-goals

- Per-set tracking.
- Cardio tracking. Cardio equipment exists at the gym but is out of scope.
- Nutrition and body weight.
- Accounts, cloud sync and social features.
- iOS, Wear OS and Play Store publishing. The app is sideloaded as an APK.

### Anti-goals (failure even if it "works")

- **Slow to pick.** Picking a workout, an exercise, a swap or starting the timer takes too many taps. This is the stated reason the lifter would abandon the app. Typing numbers into the log is fine; slow *picking* is not.
- **Blind progression.** The app keeps pushing weight up regardless of feel, stiffness or joint pain.
- **Nagging.** Reminders about frequency, streaks or missed days.
- **Personal data in the public repo.** No personal data, health notes, age, weights or gym location goes into the public repo (see §10).

## 3. Constraints and decided "how"

- **Platform: native Android.** Kotlin, Jetpack Compose, Material 3. *(My call. The lifter left technology choices to me.)*
- **Offline only, on-device storage.** No network permission needed apart from what the system file picker uses.
- **Visual style: dark and bold.** Near-black background, a single bright orange accent (about `#FF7A1A`), big numerals, large touch targets usable with sweaty hands. The lifter approved this style from mockups. Dark-only theme. *(My call.)*
- **Units: kg.**
- **Target device: Samsung Galaxy A56 on current One UI** (Android 16-era). `targetSdk` = latest stable, `minSdk` = 31 (Android 12). *(My call. Modern APIs, and still installable on most recent phones.)*
- **Samsung background-killing must be handled.** One UI puts apps into "Sleeping / Deep sleeping apps" and restricts background work. The timer must therefore:
  - **Run as a proper foreground service** with an ongoing notification (Android 14+ requires a declared foreground service type; the builder chooses the appropriate one).
  - **Request `POST_NOTIFICATIONS`** at first timer start.
  - **Show a one-time setup card** that deep-links to the app's battery settings and asks the lifter to set battery use to **Unrestricted**. The card is shown once after first launch, and again only if a timer was ever killed early.
  - **Survive screen-off and lock** without drifting. Round boundaries are computed from a monotonic start timestamp, not by counting ticks.
- **Distribution: a sideloaded APK.** The release signing key never enters the repo.
- **Repo: public, at `github.com/BlueFluxo/simple-gym`.**
  - It must contain **no secrets and no personal data**.
  - `.gitignore` already excludes keystores, `*.jks`, `.env*`, `local.properties`, secrets/signing properties, `google-services.json`, backups (`*.gymbackup`), `personal/` and `.superpowers/`.
- **Build-time fallback (Task 1).** Robolectric 4.17 fails to start on `sdk=36` (`Failed to interact with raw FileDescriptor internals`, JDK 21), so the unit tests run on `sdk=35` (`app/src/test/resources/robolectric.properties`). `compileSdk`/`targetSdk` stay 37, and the Kotlin 2.4.21 / KSP 2.3.12 / Room versions built without change.
- **Libraries: latest stable.** Every dependency is on its newest stable release (not alpha, beta or RC). Room moved to the Room 3 line (`androidx.room3` 3.0.3, `AndroidSQLiteDriver`) on 2026-10-22. `scripts/check-latest-versions.py` checks this, and is re-run at each release.

## 4. Core concepts

| Concept | Meaning |
|---|---|
| **Exercise (variant)** | A specific movement on specific equipment, e.g. "Chest press machine". It has a swap group, an equipment type, a load increment, a lower-back load (low/moderate/high), the joints it stresses, and flags for *assisted* (negative weight) and *bodyweight*. |
| **Swap group** | A movement pattern, e.g. `horizontal-press`. Exercises in the same group are interchangeable. |
| **Variant memory** | Per exercise: current working weight, last best reps, last feel, history, and plateau counter. **Each variant progresses independently.** |
| **Workout day** | An editable template, e.g. "Chest / Triceps": an ordered list of slots plus the muscle groups it trains (used by the check-in). |
| **Slot** | One position in a day. It has a default exercise, an editable swap list, sets, rep range (lo–hi) and EMOM round time. |
| **Session** | One visit doing one workout day. It holds a check-in (stiff muscles), a club (optional), and one log per exercise done. |
| **Exercise log** | weight, best reps, sets, feel (Easy / Solid / Grinder), optional hurt flag (+ joint), optional note, and a session type: `normal`, `deload`, `flush`, `held` (stiff/hurt hold) or `return` (post-layoff). |
| **Round** | Complete when **every active workout day has been done at least once** since the round started. Rounds don't depend on the calendar. If a 4th day is added, a round needs all 4. |
| **Block** | N build rounds followed by 1 deload round. N comes from the age rules (§6.5). |
| **Club** | Optional. A named gym location where specific exercises can be marked *not available*. |

### Exercise library (seed data)

`app/src/main/assets/exercise-library.json` holds 142 exercises across 40 swap groups. Each has a primary muscle, equipment type, swap group, load increment, lower-back load, `isAssisted`, `isBodyweight` and joint notes. It was researched against typical commercial-gym equipment categories. Every exercise named in the starter plan (§7) exists in it under the exact name used there.

**Builder must:**

- **Convert the free-text `jointNotes` into a structured `stressesJoints` set** using the same joint list as hurt flags: shoulder, elbow, wrist, knee, hip, lower back, neck.
- **Give each exercise a stable id** (slug of the name) for import, backup and plans.
- **No "verify this" prompts.** Everything is assumed available until the lifter marks it otherwise at a club.

## 5. Screens and flow

Reference mockups were approved in brainstorming. They're described here; the mockup file itself is kept out of the repo.

### 5.1 Home / check-in

- **Header:** block and round status, e.g. "Block 2 · Round 2 of 4".
- **Stiffness check-in:** a row of muscle chips (Back, Biceps, Chest, Triceps, Shoulders, Legs, Calves, Lower back, Neck). Tap any that feel stiff. Tapping none is valid.
- **Day list:** "Suggested today" (§6.6) is highlighted at the top. Each other day shows when it was last done and its status, e.g. "Stiff · flush option".
- **Start button.** One tap starts the suggested day; tapping another day starts that day instead.
- **Club switcher:** a small control showing the last-used club, only shown if clubs exist.
- **Other entry points:** standalone Timer, Progress, Plans (edit), Settings.

### 5.2 Workout (today's list)

- **The list:** every slot in the day. It shows done (struck through, with weight), skipped (amber, "come back"), next (highlighted) and the rest.
- **Order:** tap any slot to do it now. Order is free.
- **Add-on:** a "Core / mobility add-on" entry at the end.
- **Finish workout:** ends the session. Unfinished slots are recorded as skipped (no log).

### 5.3 Exercise

- **Header:** the variant name, with an amber hurt badge if flagged ("⚠ Shoulder · 2×").
- **Target card:** weight (big), sets, rep range, round time, and a one-line reason.
- **Swap chips:** the slot's swap list, as one-tap chips each showing its remembered weight. Ordered by how often the lifter uses them, with hurt badges. Exercises marked unavailable at the current club are hidden, and with lower-back protection on, `high` lower-back-load exercises are hidden too.
- **Search:** a "More…" search over the whole library, pre-filtered to the slot's swap group or muscle. Typing narrows it.
- **Actions:** **Start timer** (primary) and **Skip for now**. A swap only records which variant was done this session. Next time, the slot shows its *current default* again. That's the plan's default unless a block rotation (§6.3) has temporarily replaced it.
- **Hurt warning:** if the current variant has a hurt flag, an **inline amber banner** on this screen names the joint and shows the suggested joint-friendly swap as a chip. It's not a blocking dialog. Starting the timer without swapping means "continue anyway" (§6.9).
- **Stiff-muscle mode:** when this slot trains a muscle tapped stiff today, the target card shows the `held` target by default, with an inline **Flush instead** toggle (§6.7). It's not a dialog.
- **First time on a variant:** see §6.12.

### 5.4 EMOM timer

- **Display:** a big ring countdown, with "Round x / y" and the time left.
- **Adjusters:** rounds ± and round time ± (15 s steps). Editable before or during a session.
- **Defaults:** from the slot (sets × round time, typically 3 × 2:00). The lifter's last manual adjustment for that slot is remembered.
- **Cues:** beeps at 3-2-1 and at round start, plus vibration.
- **Pause / resume / stop.**
- **Runs with the screen off.** A foreground service with a lock-screen notification showing the round and time, with pause/stop controls.
- **When the final round ends,** the Log screen opens.

### 5.5 Log

- **Pre-filled** with the target weight, planned sets, the top of the rep range and feel = Solid.
- **Numbers:** fields with a numeric keypad plus ± steppers. Typing is fine.
- **Feel:** Easy / Solid / Grinder.
- **Optional:** "This hurt" opens a one-tap joint picker. An optional note.
- **Save** returns to the Workout list with the next slot highlighted.

### 5.6 Core / mobility add-on

- **Choose areas:** neck, lower back, hips, shoulders, core. Areas tapped stiff in today's check-in are pre-selected and ordered first.
- **Routine:** built from the mobility and core library groups, about 8–10 minutes.
- **Timer:** an interval timer (hold/rest, default 30 s / 10 s) that steps through the moves.
- **Logging:** just "done" per area, with no weights.
- **Nudging:** the app tracks when each area was last covered and nudges gently toward neglected areas inside this screen only. No notifications.

### 5.7 Standalone timer

The EMOM and interval timers can be opened from Home without an exercise, like a standalone interval-timer app. Nothing is logged.

### 5.8 Progress

- **Pick an exercise variant** and see a line chart of working weight per session.
- **Labels:** best reps on each point.
- **Faded points:** deload, flush and return sessions.
- **Markers:** sessions where a hurt flag was logged.
- **Assisted variants plot upward as assistance decreases.**

### 5.9 Plans (editor)

- **Days:** create, rename, reorder, archive and delete.
- **Slots within a day:** add, remove and reorder. Set the default exercise, sets, rep range and round time, and edit the swap list (add from library search, remove, reorder).
- **Exercises:** add custom exercises to the library (name, muscle, equipment, swap group, increment, lower-back load, assisted/bodyweight flags).

### 5.10 Settings

- **Profile:** age, and lower-back protection on/off.
- **Clubs:** add, rename and delete. Per club, a list of exercises marked unavailable. Exercises can also be marked unavailable from the exercise screen's chip long-press: "Not at this club".
- **Increments:** overrides per equipment type, e.g. the dumbbell step.
- **Data:** import starting data, save backup, restore backup.

### 5.11 First launch

1. Welcome screen offering **Import starting data** (system file picker; Google Drive appears there) or **Set up manually**.
2. Manual setup asks only for age, lower-back protection and, optionally, current working weights for the starter plan's default exercises. Each can be left blank.
3. The starter plan (§7) and the library are always loaded.

### 5.12 Tap rule (the anti-goal, made concrete)

On a normal day:

- **Open app → lifting:** at most 2 taps (start the suggested day, then start the timer on the highlighted exercise).
- **One tap each:** a swap, starting the timer, and jumping to any exercise.

These rules apply to **picking**. The log screen may take as many taps as the numbers need. The budget holds on **every** day, including stiff and hurt days: stiffness and hurt handling are inline defaults and banners with an override, never blocking dialogs. The only modal prompts allowed are on first launch and destructive confirmations (delete or restore).

## 6. Coaching engine

All rules work **per exercise variant**.

- **Excluded from progression and plateau detection:** logs of type `deload`, `flush` and `held`, plus any log with a hurt flag. They're recorded and charted, but the next target is computed from the last **baseline log**.
- **Baseline log:** a `normal` or `return` log with no hurt flag.
- **Return logs are baselines on purpose.** After a comeback session, progression continues from the comeback weight, so the lifter is never jumped straight back to the pre-layoff weight.

This section works only through existing rules reacting to logged data. "Learning" means these rules plus the stiffness-timing estimate in §6.6. There is no extra adaptive layer that rewrites block length or age rules.

### 6.1 Load steps and weight direction

- **The step is the variant's load increment.** The library value is authoritative: 2.5 kg for almost everything (barbell, dumbbell, EZ bar, cable, pin- and plate-loaded and assisted machines), 2 kg for a few light dumbbell isolation moves. None for bodyweight and band. Overrides are per equipment type in Settings, and per exercise. Increases are exact steps. Percentage reductions (deload, return, plateau, flush) round **down to the lighter step**. Rep-range conversions round to the nearest step.
- **Assisted variants** (e.g. assisted pull-up, assisted dip) store weight as a negative number (−70 = 70 kg of assistance).
  - "Increase" means **toward zero** (−70 → −67.5).
  - "Decrease" means more assistance.
  - Percentage reductions apply to the assistance in the safe direction: −10% on −70 → about −77.5.
- **Bodyweight variants** progress by reps only. Once the top of the range is easy, the reason line suggests a harder variant from the same swap group or adding load.

### 6.2 Progression (from the last baseline log: weight W, best reps R, feel F, slot range lo–hi)

| Situation | Next target |
|---|---|
| R ≥ hi + 3 and F = Easy | W + 2 steps (catches under-estimated starting weights) |
| R ≥ hi and F ∈ {Easy, Solid} | W + 1 step |
| R ≥ hi and F = Grinder | Hold W, aim for hi cleanly |
| lo ≤ R < hi | Hold W, target R + 1 reps |
| R < lo and (F = Grinder or R ≤ lo − 3) | W − 10% (rounded to step) |
| R < lo otherwise | W − 1 step |

- The **reason line** always states the rule in plain words.
- **No sets to failure** are programmed. Target is 1–2 reps in reserve. *(My call, for joints. The lifter's old sheet said "last set to failure".)*

### 6.3 Plateau

- **Counter:** per variant and per range type (§6.4), count consecutive baseline logs with no increase in weight *and* no increase in best reps at the same weight.
- **At 3:** next target is W − 10%, then normal progression rebuilds from there.
- **Rotation offer:** at the same moment, an inline banner offers "Rotate to another variation for this block?" with the slot's swap chips.
  - **Accepting stores a block-scoped default override on the slot.** The chosen variant becomes the slot's current default until the current block ends. At that point the slot returns to its plan default, and the override is shown on the first session of the new block as a one-tap "Keep [variant] as default?" chip.
  - **The rotated variant uses its own memory** and plateau counter.
- **Reset:** the counter resets on any progress. It's independent per variant and per range type, so swapping or changing range doesn't touch another counter.

### 6.4 Blocks, rounds and deloads

- **Block shape:** N build rounds then 1 deload round (N from §6.5).
- **Deload round:** every day's next session is a deload. Each slot targets weight −10% (rounded) and sets −1 (minimum 2), same rep range. Logged as `deload`.
- **What counts as "a day done" for the round:** a session of that day with at least half its slots logged as `normal`, `held`, `return` or `deload`. Flush-only sessions don't count. Doing a day twice within a round is allowed and doesn't complete the round faster.
  - **During a deload round,** a day counts once it has had a deload session that meets the half-slots rule.
  - **Partial deload rounds:** a deload round left partly done for more than 14 days is closed automatically, and the next block starts.
- **Block 1 always uses the base range.**
- **Rep-range alternation (for ages where §6.5 enables it):** blocks alternate between the slot's base range and a **higher-rep range**, base + 4 at both ends (8–12 → 12–16).
  - **Cap:** the top of the range never goes above 20.
  - **Exclusions:** slots whose base top is already ≥ 15, and assisted or bodyweight variants, keep their base range in every block. For assisted variants the e1RM conversion is meaningless.
  - **Entering a higher-rep block:** the target weight is converted with an estimated-1RM formula (e.g. Epley) and rounded to step.
  - **Returning to base:** the target is converted back from the latest higher-rep logs.
  - **Variant memory keeps both:** a working weight per range type, so switching back doesn't lose the old number.

### 6.5 Age rules (age comes from the profile; no age is hard-coded)

| Age | Build rounds per block (N) | Rep-range alternation | Max increase per session |
|---|---|---|---|
| < 40 | 5 | off | 2 steps |
| 40–54 | 3 | on | 2 steps (only via the "hi + 3 easy" rule), otherwise 1 |
| 55+ | 2 | on | 1 step |

Age sets these rules and nothing else. The rest of the personalisation comes from the per-variant rules reacting to logged data (§6 intro).

### 6.6 Stiffness check-in and day suggestion

- **Mapping check-in chips to library muscles:**

  | Chip | Library muscles |
  |---|---|
  | Legs | Quads, Hamstrings, Glutes |
  | Back | Back |
  | Chest | Chest |
  | Shoulders | Shoulders |
  | Biceps | Biceps, Forearms |
  | Triceps | Triceps |
  | Calves | Calves |
  | Lower back | no library muscle |
  | Neck | no library muscle |

  - **Core** and **Mobility** are never blocked.
  - **A day's "muscles" are the chips** it lists (editable in Plans), defaulting from its slots' primary muscles.
- **Lower back and Neck don't block days.**
  - **A stiff lower back:** for every slot whose current default has `moderate` or `high` lower-back load, the lowest-load option in its swap list becomes today's suggested variant, shown first and highlighted. The lower-back add-on area is also pre-selected.
  - **A stiff neck:** pre-selects the neck add-on area and puts shrug and upright-row variants after other options in chip order.
- **Stiff muscles block a day.** A day is "blocked" if any of its chips is tapped stiff today.
- **Suggested day:** the unblocked day with the longest time since it was last done. Ties go to the day not yet done in the current round.
- **If every day is blocked:** suggest the day whose stiff muscles have had the most recovery time, with a **flush** prompt.
- **Learning recovery:** each check-in tap records (muscle, date). The app estimates per muscle how many days after a session it usually stays stiff. It uses that to order suggestions and to write "Legs: usually loose after 3 days" in the day card.

### 6.7 Training a stiff muscle

When the lifter starts a day that trains a muscle tapped stiff, each affected slot offers:

- **Train normally (`held`):** a longer-warm-up note, and weight held at last normal W (no increase).
- **Flush (`flush`):** about 45% of W rounded to step, 15–20 reps, 2–3 sets, short round time (default 1:00). Never used for progression.

`held` is the default and needs no tap. **Flush instead** is an inline toggle on the exercise screen (§5.3), per slot. A flush finisher on another day — a couple of light sets for a stiff muscle at the end of an unrelated workout — is offered as an optional extra slot on the Workout screen.

### 6.8 Layoff

- **Measured per swap group, not per variant,** so rarely-used swaps aren't penalised. The gap is the days since the lifter's last log of *any* variant in the same swap group.
  - A rarely-used variant whose movement pattern has been trained recently is **not** a return. Its own last baseline weight is used as is.

| Gap since that movement was last trained | Return target |
|---|---|
| < 10 days | none (normal) |
| 10–20 days | W − 10% |
| 21–42 days | W − 20% |
| > 42 days | W − 30% |

- **The return log becomes the new baseline** (§6 intro). Normal progression continues from it, never from the pre-layoff weight.
- **For assisted variants,** the percentage is applied as more assistance (§6.1).

### 6.9 Hurt flags

- **Flag:** logging "This hurt" + joint adds to the variant's hurt count for that joint. The log itself is excluded from progression (§6 intro).
- **Badge (variant-level):** an amber joint badge with the count appears everywhere the variant is shown.
- **Joint-level caution (softer):** for 14 days after any hurt flag on a joint, every *other* variant that stresses that joint (via `stressesJoints`) shows a small grey "Shoulder flagged recently" note. It also gets a hold instead of an increase. Its weight doesn't go up until the window passes or the lifter logs it pain-free twice.
- **Before starting a flagged variant:** the inline banner (§5.3) suggests the best swap from the slot's list that doesn't stress that joint. If none qualifies, the lowest-stress option from the swap group is suggested.
- **Continuing anyway:** the target is **W − 1 step**, below the weight that hurt, logged as `held`.
- **Clearing:** manually, or automatically after **3 consecutive pain-free logs** of that variant.
- **Frequency:** flagged variants rank lower in swap chip order.

### 6.10 Lower-back protection

When on:

- `lowerBackLoad = high` exercises (deadlifts, RDLs, bent-over rows, …) never appear in suggestions, chips, the starter plan or hurt-prompt swaps.
- They remain findable in search with a warning badge.
- When choosing between swaps, low-load options are preferred.

### 6.11 Combining modifiers

If more than one applies (deload, stiff hold, return, hurt hold, joint caution), compute each target and use the **single most conservative** one: lowest weight, fewest sets. Don't multiply them. Flush is a mode chosen by the lifter, not a modifier. The reason line names the modifier that won.

### 6.12 First time on a variant (cold start)

- **No history (never logged, nothing imported):** the target card shows **no weight**. It says: "New: start light, with a weight you could lift about 15 times."
  - **Reference value:** if another variant in the same swap group with the **same equipment type** has a baseline, the card shows that variant's weight as a hint: "Dumbbell bench press: 22.5kg". It's labelled as a hint, not a target.
  - **No ratios across equipment types,** e.g. barbell → machine, because pin stacks vary too much to be safe.
  - The weight field on the Log screen is then blank and must be typed.
  - The first log becomes the baseline, and the "hi + 3 easy" rule (§6.2) catches up fast if the guess was light.
- **Swap chips for never-used variants** show "new" instead of a weight.
- **Imported starting weights** (§8):
  - **What they mean:** a baseline log dated the import day, with W = imported weight. If reps are missing, best reps = the slot's lo and feel = Solid, so the first target is "hold W, aim for lo + 1".
  - **Layoff:** the import date counts as training for the layoff rule, so the first session isn't treated as a return.

## 7. Starter plan (generic, committed to the repo)

Default exercise first; swap list in brackets. Defaults: 2:00 round time; for flush, 1:00.

**Chest / Triceps** (muscles: Chest, Triceps, Shoulders)

| Slot | Sets × reps | Swaps |
|---|---|---|
| Bench press | 3 × 8–12 | Chest press machine, Dumbbell bench press, Machine press (plate-loaded), Push-ups |
| Machine shoulder press | 3 × 8–12 | DB shoulder press, Barbell overhead press |
| Upright row (dumbbell) ("Up rows") | 3 × 10–12 | Upright row (cable), Upright row (barbell) |
| Triceps machine | 3 × 10–12 | Triceps pushdown (cable), Band triceps pushdown |
| Triceps pushdown (cable) ("Triceps pulls") | 3 × 10–12 | Overhead cable triceps extension, Overhead dumbbell triceps extension |

**Back / Biceps** (muscles: Back, Biceps)

| Slot | Sets × reps | Swaps |
|---|---|---|
| EZ-bar curl | 4 × 6–10 | Preacher curl, Bicep machine, Dumbbell curl |
| Incline dumbbell curl | 3 × 8–12 | Bayesian cable curl, Drag curl |
| Chin-up | 4 × 6–10 | Assisted pull-up, Underhand lat pulldown, Lat pulldown |
| Chest-supported row (machine) | 3 × 8–12 | Row machine, Cable row, Dumbbell row, Incline bench chest-supported row (dumbbell) |
| Lat pulldown | 3 × 10–12 | Assisted pull-up, Band lat pulldown, Straight-arm pulldown |
| Hammer curl | 3 × 10–12 | Rope hammer curl, Bayesian cable curl |
| Rear delt pull (cable) | 3 × 12–15 | Reverse pec deck, Lying rear delt raise, Face pull |

**Legs / Calves** (muscles: Legs, Calves)

| Slot | Sets × reps | Swaps |
|---|---|---|
| Barbell back squat | 3 × 8–12 | Seated leg press, Leg press, Goblet squat, Smith machine squat, Hack squat |
| Walking lunges | 3 × 10–12 / leg | Reverse lunge (dumbbell), Bulgarian split squat, Dumbbell step-up |
| Leg extensions | 3 × 10–15 | Sissy squat |
| Lying leg curl | 3 × 10–12 | Seated leg curl, Cable standing leg curl |
| Hip thrust (barbell) | 3 × 8–12 | Hip thrust machine, Dumbbell hip thrust, Glute bridge |
| Hip abductor machine (out) | 2 × 12–15 | Cable hip abduction, Banded lateral walk |
| Hip adductor machine (in) | 2 × 12–15 | Cable hip adduction |
| Standing calf raise machine | 4 × 10–15 | Seated calf raise machine, Leg press calf raise |

- **Swap lists may cross swap groups** where the lifter treats them as alternatives, e.g. squat ↔ leg press. A slot's swap list is explicit data, pre-filled from its group plus these additions.
- **No deadlifts or RDLs** appear in the starter plan.

## 8. Personal data (kept out of the repo)

- **The lifter's data lives elsewhere:** profile (age, lower-back protection), clubs and current working weights live only on the phone. They arrive via the **starting-data import file**.
- **The builder defines the import format.** A versioned JSON format covering profile, clubs (with unavailable exercises), per-exercise working weights, and optionally hurt flags and extra custom exercises, matched to library exercises by stable id.
- **Repo copy:** a fictional example file can be committed (`docs/examples/starting-data.example.json`).
- **After the format is fixed,** the brainstorming session will create the lifter's real file privately in their Google Drive and in the git-ignored `personal/` folder. It isn't part of the build.
- **Backup format:** a full export of all data, readable by Restore. Saved via the system "save as" picker, so Google Drive works, with a `.gymbackup` extension (git-ignored).

## 9. Calls I made (please check)

1. **Technology:** native Kotlin + Jetpack Compose + Material 3, offline, sideloaded APK.
2. **Dark-only theme**, with an orange accent.
3. **Plan defaults:** the starter plan's exact sets and rep ranges for exercises the old sheets didn't specify; default round time 2:00.
4. **Feel scale:** Easy / Solid / Grinder, and the progression table in §6.2, including +2 steps on "hi + 3 easy".
5. **No sets to failure;** aim for 1–2 reps in reserve.
6. **Thresholds:** −10% for deload, return and plateau reset; layoff threshold 10 days; plateau after 3 sessions.
7. **Rep-range alternation:** base + 4 reps, with an estimated-1RM weight conversion.
8. **Age bands and block lengths** in §6.5.
9. **Flush settings:** about 45% weight, 15–20 reps, 2–3 sets, 1:00 rounds.
10. **Hurt flags** clear after 3 consecutive pain-free logs.
11. **Most-conservative-wins** rule for combining modifiers.
12. **No "verify equipment" prompts.** Everything is available until marked unavailable at a club.
13. **No cardio group.** "Wall kicks" and "High kicks" are treated as dynamic hip mobility moves.
14. **Triceps machine type** (seated extension or dip style) is left to the lifter to rename in the library if needed.
15. **Cold start:** no weight is shown for never-used variants, only a same-equipment hint and "start light" (§6.12). Imported weights are treated as a baseline at the bottom of the range.
16. **Layoff:** measured per swap group (movement pattern), with a scaled cut of −10 / −20 / −30%. The return log becomes the new baseline (§6.8).
17. **Inline banners instead of dialogs** for stiff and hurt handling, so the tap budget holds on every day (§5.12).
18. **Hurt flags:** variant-level badges plus 14-day joint-level caution on other exercises loading that joint. "Continue anyway" = 1 step lighter (§6.9).
19. **Block rotation:** a block-scoped default override on the slot (§6.3).
20. **Check-in muscle mapping;** stiff lower back steers to low-load swaps (§6.6).
21. **A day counts as done** at ≥ half its slots logged. Flush-only sessions don't count (§6.4).
22. **Higher-rep blocks:** capped at 20 reps. Slots with a base top ≥ 15, and assisted or bodyweight variants, are excluded (§6.4).
23. **Load increments:** 2.5 kg default for machines and dumbbells, matching the 2.5 kg steps common on pin-stack machines.
24. **Percentage reductions round toward the lighter step** (60 −10% → 52.5, not 55), for joint safety (§6.1).
25. **Range conversions target the top of the new range.** When converting between base and higher-rep ranges, the weight is converted for the **top** of the new range, then progression proceeds from "hold, aim lo + 1" (§6.4).

## 10. Left to the builder

- Architecture, module layout, database schema (Room or similar) and migrations.
- Exact component design within the approved dark-and-bold style.
- Timer implementation details: foreground service type, audio focus, wake handling.
- The import/backup JSON schema and versioning (§8).
- Converting `jointNotes` into `stressesJoints` (§4).
- The estimated-1RM formula, and charting library choice.
- Test strategy. The coaching engine (§6) should be pure, deterministic Kotlin with thorough unit tests, because it's where wrong behaviour hurts the lifter.
- Build and signing setup. Keystore and passwords via git-ignored local properties or environment, never committed.
