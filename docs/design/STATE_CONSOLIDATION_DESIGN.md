# SimpleCoil Shared State: One Source of Truth — Design Document

Status: **IMPLEMENTED (2026-10-13), not yet committed or tested on a device.** All open questions are resolved (§7).
§8 records what was built and where it differs from this plan.
Branch audited: `V2-refactor` @ `bba0c67`, plus the uncommitted working-tree fix that added `Globals.setGameMode()`
(§2.1).
Date: 2026-10-13

Revision 2 (2026-10-13): decisions recorded. "Team Mode" is used everywhere the user sees it (Q1). All of §6 is done
in one pass instead of being staged (Q2). `Globals` is deleted, and its constants and types move to the classes that
own them (Q3). `MapFragment` is rewired onto `MapLocationManager` ahead of upcoming GPS work (Q4).

---

## 0. Summary

On a server-less client, picking **4 Teams** still cycled player IDs as 2 teams of 16. The game mode is stored twice:
`Globals.mGameMode` and `GameSettingsRepository.gameMode`. The mode button wrote the first copy and the team math read
the second. The interim fix makes the button write both. It treats the symptom, not the cause.

The cause is general. `REFACTOR_DESIGN.md` §1.4 (C3) already flagged that `Globals` and the three repositories are
"two sources of truth", and §5 left consolidating them out of scope. This document is that follow-up. The audit (§1)
found:

- **~40 settings and state values held in two places.** `Globals` copies its fields into the repositories **once**,
  in `Globals.getInstance()` → `syncToRepositories()`, and never again. Every later write to a `Globals` field
  leaves the repository copy stale. Every write that goes only to a repository leaves the `Globals` copy stale.
- **Two confirmed bug paths and several latent ones.** Team mode is the one that shipped. GPS mode is the next one
  waiting to happen (§2.2).
- **Hidden write-backs inside getters.** `Globals.getTeamSizes()` pushes `Globals.mGameMode` into the repository on
  every call, so a change made directly on the repository is silently reverted (§2.1).
- **Three unrelated lock domains guarding the same player data.** These are the six `Globals` semaphores, the
  `PlayerRepository` monitor and the `GameRoster` lock. Each makes its own section atomic but they don't exclude each
  other (§2.5).

This document proposes:

1. **Team mode first (§3).** Rename "game mode" to **team mode**. Replace the two `int` copies and the separate
   `teamSizes` array with one immutable `TeamLayout` value (mode + team sizes) owned by `GameSettingsRepository`. It
   is published atomically and read as a single snapshot. The wire protocol and saved preferences keep their current
   keys and values.
2. **The repositories become the single source of truth for everything else (§4).** `Globals` fields are migrated
   group by group to repository accessors, and the one-shot sync and the back-sync hooks are deleted. `Globals` ends
   as a constants holder, or is removed entirely.
3. **One lock per piece of data (§5).** The `Globals` semaphores and the exposed internal arrays/maps are retired in
   favour of repository methods that do their compound updates internally.

The work is staged so each step builds, passes the existing suite, and can ship on its own (§6).

---

## 1. Audit: what is stored twice

Method: for every mutable field in `Globals` and every accessor in `GameSettingsRepository`, `GameStateRepository` and
`PlayerRepository`, list who writes and who reads each copy outside `Globals.java` and the repository itself. A copy
that is written but never read is a **dead mirror**: harmless today, but a trap for the next reader. A value whose
two copies are each read somewhere is **split-brain**, and that is where bugs come from.

Scale: there are about **450** reads/writes of `Globals` fields (`Globals.getInstance().mX` / `g.mX`) outside
`Globals.java`. `mGameMode` alone accounts for 60 of them across 10 files.

### 1.1 Settings (`Globals` ↔ `GameSettingsRepository`)

| Value | `Globals` copy written by | Repository copy written by | Repository copy read by | Classification |
|---|---|---|---|---|
| Game/team mode | `GameViewModel` (via `setGameMode`), `DedicatedServerActivity:408/420/432`, `TcpServer:960` (ADMIN_GAMEMODE), `TcpClient:852` | `Globals.setGameMode/getTeamSizes/setTeamSizes/resetDefaultTeamSizes` (side effects), `DedicatedServerViewModel.setGameMode` (unused) | `PlayerRepository.calcNetworkTeam/findAutoSort…/findOpenPlayerIDOnTeam`, `TcpServer:1375`, `MapLocationManager:242`, `DedicatedServerViewModel:89` | **Split-brain — shipped bug** (§2.1) |
| Team sizes | `Globals.mTeamSizes` (a cache: written inside `Globals`, never read outside) | `Globals.setTeamSizes/resetDefaultTeamSizes`, `GameSettingsRepository.setGameMode` | `PlayerRepository`, `GameRoster:645` and `TcpServer:448` via `Globals.getTeamSizes()` | Dead cache + coupled to mode (§2.1) |
| GPS mode | `DedicatedServerActivity:284/466`, `TcpClient:863` | `DedicatedServerViewModel.setGpsMode` (never called) | `MapLocationManager:256` (class not instantiated), `DedicatedServerViewModel:92` (LiveData not observed) | **Split-brain, latent** (§2.2) |
| Start (countdown) time | `TcpClient`, `DedicatedServerActivity`, `PlayerSettingsAlertDialog` | Dual-written by hand at `TcpClient:867`, `DedicatedServerActivity:283/645` | `DedicatedServerViewModel:91` | Kept in step manually; fragile |
| Allow choose teams | `TcpClient`, `DedicatedServerActivity` (never read) | Dual-written at `TcpClient:873`, `DedicatedServerActivity:307/313` | `GameViewModel:657`, `TcpServer:467/1367` | `Globals` copy is a dead mirror |
| Only server settings | `TcpClient` | `syncToRepositories` only | `DedicatedServerViewModel:94` | Split, latent (repository is always the default `true`) |
| Game limit / time / score / lives limits | `GameViewModel`, `TcpClient`, `DedicatedServerActivity` | `DedicatedServerViewModel.setGameLimit` (unused) | `DedicatedServerViewModel:90` | Split, latent |
| Respawn shield time | `PlayerSettingsAlertDialog:378`, `TcpClient:779` | Dual-written at `PlayerSettingsAlertDialog:379` | nobody | Repository copy is a dead mirror |
| Health, reload, reload time, respawn time, damage, override lives, allow-player-settings, reload-on-empty, shot-mode allowances, firing mode, server recoil setting, use-GPS | various | `syncToRepositories` only | nobody | Repository copies are dead mirrors |

`GameConfig` (`domain/session/GameConfig.kt`), the engine's view of settings, is the clearest illustration. It reads
`gameMode` from `Globals.mGameMode` but `networkTeam()` from `PlayerRepository` → `GameSettingsRepository`. That puts
the two halves of the friendly-fire decision in `HitResolver` (`freeForAll` and "same team?") on different copies
(§2.1).

### 1.2 Game state (`Globals` ↔ `GameStateRepository`)

| Value | Notes |
|---|---|
| `mGameState` / `gameState` | Dual-written at `TcpServer:320-321`. `GameConfig.gameState`, `GameViewModel` and `DedicatedServerActivity:168` write only `Globals`. The repository copy and its listeners are read only by `DedicatedServerViewModel`. On the server they agree. On a client the repository is never updated. |
| `mServerGameTimeRemaining`, `mServerIP` | Repository copies are written only by the one-shot sync. `getServerGameTimeRemaining()` is read by `DedicatedServerViewModel`, so it always reports 0 unless something else sets it. Latent. |

### 1.3 Local player identity (`Globals` ↔ `PlayerRepository`)

| Value | Notes |
|---|---|
| `mPlayerID` / `playerID` | Two-way sync hooks (`PlayerRepository.setPlayerID` → `Globals.updatePlayerIDFromRepo`, `Globals.setPlayerID` → `setPlayerIDDirect`). Nobody calls either setter: all writers assign `g.mPlayerID` directly, so the repository copy is never updated and nobody reads it. |
| `mPlayerName`, `mIsAdmin`, `mPairedGrenadeID` | Same pattern. The repository copies are dead mirrors. |

### 1.4 Shared collections

`Globals.mIPTeamMap`, `mTeamIPMap`, `mTeamPlayerNameMap`, `mGPSData`, `mPlayerSettings` and `mGrenadePairings` are
**aliases** of the repository's own objects (`Globals` constructor, line ~150). That means one copy, not two, but the
repository's internals are exposed to unsynchronized callers. `mGrenadePairings` is the raw backing array from
`getGrenadePairingsInternal()`.

`GameRoster` owns the server's authoritative `PlayerRecord`s and keeps `PlayerRepository`'s maps "in step for the
screens that read them" (`GameRoster.java` class comment). This is a **deliberate, documented projection**, not an
accident. It is in scope only for its locking (§2.5).

### 1.5 Persistence and UI copies (reviewed, mostly fine)

- **SharedPreferences.** The client (`UserPreferencesRepository`) and the dedicated server
  (`DedicatedServerActivity:263/411`, raw `SharedPreferences`) write the same `"GameMode"` key in the same file
  through two different APIs. They share one stored value, which is fine, but two code paths own its format. The
  server's admin path (`TcpServer:960`) changes the mode without saving it.
- **Derived caches.** `BlasterSessionEngine.networkTeam` is recomputed by `GameViewModel.displayCurrentTeam()` on
  every team or mode change. That's acceptable once the mode has a single source (§3.5).
- **Distinct concepts that only look duplicated.** `GameViewModel.selectedPlayerId` (the player's chosen ID) and
  `Globals.mPlayerID` (the ID in effect, possibly assigned by a server) are different things. Leave them alone.
- **UI.** `DedicatedServerActivity`'s mode button text is set by hand in three places
  (`265-269`, `410/422/434`, `909-914`). It should observe the repository instead (§3.4).

---

## 2. Findings in detail

### 2.1 Team mode (shipped bug; interim fix in place)

- **What happened.** `GameViewModel.onGameModeSelected` assigned `g.mGameMode`. Team membership comes from
  `PlayerRepository.calcNetworkTeam`, which reads `GameSettingsRepository.getGameMode()/getTeamSizes()`, so it stayed
  on 2 × 16. Connected clients escaped only by accident: `TcpClient` calls `Globals.setTeamSizes()` right after
  setting the mode, and that call happens to push the mode into the repository.
- **Gameplay effect, not just display.** `HitResolver` takes `freeForAll` from `Globals` and `networkTeam` from the
  repository. In "4 Teams" on a server-less client, friendly fire was judged on the 2-team layout.
- **Getter with side effects.** `Globals.getTeamSizes()` (line 259) calls
  `GameSettingsRepository.setGameMode(mGameMode)` first. If the repository mode was changed by any other path, for
  example `DedicatedServerViewModel.setGameMode`, the next `getTeamSizes()` reverts it. Because `setGameMode` resets
  sizes on change, it also **discards custom team sizes**.
- **Not atomic.** Mode and sizes are separate fields. "Set mode → reset sizes → rebalance" runs as three separately
  locked steps, on the UI thread (`DedicatedServerActivity`, `GameViewModel`), a `TcpServer` connection thread
  (`ADMIN_GAMEMODE`) and the `TcpClient` reader thread. A reader on another thread can see the new mode with the old
  sizes, for example 4 teams with a `{16,16}` array. `calcNetworkTeam` then falls back to `return numTeams`.
- **No validation.** `ADMIN_GAMEMODE` accepts any integer.
- **Interim fix (uncommitted).** `Globals.setGameMode()` writes both copies. It is used by `GameViewModel` only. §3
  replaces it.

### 2.2 GPS mode (latent split-brain)

`MapFragment:410` (live) reads `Globals.mGPSMode`. `MapLocationManager:256` (not currently instantiated) reads
`GameSettingsRepository.getGpsMode()`, which nothing ever sets, so it is permanently `GPS_ALL`. If the map code is
moved onto `MapLocationManager`, as the repository-based design intends, "teammates only" GPS stops hiding enemies.
Nothing would fail to compile and no current test would catch it.

### 2.3 `DedicatedServerViewModel` settings LiveData (latent)

`DedicatedServerViewModel` exposes LiveData for mode, limits, start time, GPS mode, only-server-settings and
allow-choose-teams. `DedicatedServerActivity` observes only server IP, player count and phase. The rest would show
defaults or stale values the moment someone observes them, because the activity writes `Globals`.

### 2.4 Dead mirrors

Roughly 25 repository fields (§1.1 last row, §1.3) are written only by `syncToRepositories()` and read by nobody.
`Globals.mTeamSizes` and `Globals.mAllowChooseTeams` are the reverse: written, never read. Dead mirrors cost nothing at
run time, but they invite exactly the mistake that caused the team-mode bug: a new reader picks the wrong copy.

### 2.5 Locking

| Data | Guarded by |
|---|---|
| Player maps (IP↔ID, names, GPS, settings) | `Globals` per-map `Semaphore`s (TcpServer 16 sites, TcpClient 7, others 4) **and** `PlayerRepository`'s monitor (`synchronized` methods) **and** `GameRoster`'s lock (when it keeps the maps in step) |
| Grenade pairings | `Globals.mGrenadePairingsSemaphore` around raw array writes (`TcpServer:1199`) **and** `PlayerRepository`'s monitor (`removeGrenadePairings`, `clearGrenadePairings`, …) |
| Team mode + sizes | `Globals` monitor (`setGameMode`/`getTeamSizes`) **and** `GameSettingsRepository` monitor; mode field also `volatile` and written with no lock |

Holding a `Globals` semaphore doesn't exclude a thread inside a `PlayerRepository` method, and the reverse is also
true. The maps are `ConcurrentHashMap`, so single operations are safe. Multi-step updates are not, for example
`reassignPlayer`, or the array read-modify-write at `TcpServer:1035-1038` against a concurrent `removeGrenadePairings`.
Lock order today is `Globals` → `GameSettingsRepository` and `PlayerRepository` → `GameSettingsRepository`, with no
cycle. The new design must keep `GameSettingsRepository` a leaf (§5).

---

## 3. Team mode: target design

### 3.1 Naming

"Game mode" becomes **team mode** in code: `TeamMode`, `teamMode`, `setTeamMode`. The **wire protocol keeps its
names and values**: JSON key `"gamemode"`, message `ADMIN_GAMEMODE`, values 1/2/4. This keeps older clients and
servers interoperable. The SharedPreferences key `"GameMode"` is also kept, so users don't lose their setting. The
user-facing label is open question Q1.

### 3.2 Types

```kotlin
enum class TeamMode(val wireValue: Int, val teamCount: Int) {
    FREE_FOR_ALL(1, 0), TWO_TEAMS(2, 2), FOUR_TEAMS(4, 4);
    companion object {
        /** Null for anything the protocol doesn't define; callers reject the message. */
        fun fromWire(value: Int): TeamMode? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Mode and team sizes always change together; an instance is immutable. */
class TeamLayout private constructor(val mode: TeamMode, sizes: IntArray) {
    private val sizes = sizes.copyOf()
    fun teamSizes(): IntArray = sizes.copyOf()
    fun teamOf(playerId: Byte): Int            // replaces PlayerRepository.calcNetworkTeam's arithmetic
    fun teamStartId(team: Int): Int
    fun teamEndId(team: Int): Int
    fun withSizes(sizes: IntArray): TeamLayout // validates: count == teamCount, sum == MAX_PLAYER_ID
    companion object {
        fun defaultFor(mode: TeamMode): TeamLayout // FFA → [], 2 → [16,16], 4 → [8,8,8,8]
    }
}
```

`TeamLayout` is pure Kotlin with no Android types. It sits beside `GameConfig` in `domain/` (the module is already
mixed Java/Kotlin) and is unit-tested directly.

### 3.3 Ownership and thread safety

`GameSettingsRepository` owns one field:

```java
private final AtomicReference<TeamLayout> teamLayout = new AtomicReference<>(TeamLayout.defaultFor(TWO_TEAMS));

public TeamLayout getTeamLayout() { return teamLayout.get(); }
public TeamMode getTeamMode() { return teamLayout.get().getMode(); }
/** Changing the mode resets team sizes to the defaults for that mode. No-op if unchanged. */
public void setTeamMode(TeamMode mode) { teamLayout.updateAndGet(l -> l.getMode() == mode ? l : TeamLayout.defaultFor(mode)); notify…; }
public void setTeamSizes(int[] sizes) { teamLayout.updateAndGet(l -> l.withSizes(sizes)); notify…; }
public void setTeamLayout(TeamLayout layout) { teamLayout.set(layout); notify…; } // server → client, mode+sizes in one step
```

- **Atomic.** Mode and sizes are one immutable object, so no thread can observe a mixed state. `updateAndGet` makes
  read-modify-write safe without a lock, and `GameSettingsRepository` stays a lock-order leaf.
- **Snapshot reads.** Any computation that needs mode and team membership together takes **one** `getTeamLayout()`
  and works on it. `HitResolver`'s context gets `layout.mode == FREE_FOR_ALL` and `layout.teamOf(id)` from the same
  snapshot. `PlayerRepository.findAutoSortPlayerID` and `findOpenPlayerIDOnTeam` take the snapshot once at the top
  instead of re-reading settings inside their loops.
- **Change notification.** A `StateFlow<TeamLayout>` (Kotlin callers) and a listener list (Java callers), fired after
  the swap. This replaces the hand-written refresh calls in `DedicatedServerActivity` and lets `GameViewModel`
  redisplay the team without every write site having to remember to.
- **Server sequencing.** "Change mode → rebalance → broadcast" stays under `GameRoster`'s lock, as rebalancing is
  today. The roster reads one snapshot at the start of the rebalance.

### 3.4 Call-site changes

| Site | Change |
|---|---|
| `Globals.mGameMode`, `mTeamSizes`, `setGameMode`, `getTeamSizes`, `setTeamSizes`, `resetDefaultTeamSizes`, `getTeamStartID/EndID`, `calcNetworkTeam` | Deleted. Callers use the repository or a `TeamLayout` snapshot. |
| `GameSettingsRepository.gameMode`, `teamSizes`, `get/setGameMode`, `get/setTeamSizes`, `resetDefaultTeamSizes`, `getTeamStartID/EndID` | Replaced by §3.3 |
| `PlayerRepository.calcNetworkTeam` and friends | Delegate team arithmetic to `TeamLayout`. Keep the occupancy logic. |
| `TcpClient:852-859` | `TeamMode.fromWire(…)` → build a `TeamLayout` with the received sizes → one `setTeamLayout` |
| `TcpServer:960` (ADMIN_GAMEMODE) | Validate with `fromWire`, ignore invalid values, `setTeamMode`, save to preferences (it currently doesn't) |
| `TcpServer:448` (game info), `:1375` | Read one snapshot; serialize `mode.wireValue` and sizes from it |
| `DedicatedServerActivity` (28 refs) | Menu writes `setTeamMode` + save. The button text comes from the listener. |
| `GameViewModel` (11), `GameConfig.gameMode`, `MapFragment`, `PlayerSettingsAlertDialog`, `PlayerDisplayDataListAdapter`, `NetworkSessionGateway`, `GameRoster` | Read `getTeamMode()` / snapshot |
| `UserPreferencesRepository.gameMode()` / `DedicatedServerActivity` raw prefs | One `teamMode()` / `saveTeamMode()` pair, same key and int values. The server activity uses it too. |
| `MapLocationManager`, `DedicatedServerViewModel` | Point at the new accessors (they compile against the repository already) |

### 3.5 Behaviour that must not change

- Changing the mode resets sizes to the defaults, and the dedicated server then rebalances (`rebalanceAllPlayers(false)`).
- A post-game mode change performs the implicit Reset first (`NETWORK_RECONNECT_DESIGN.md` §3.1.1).
- A client in a game receives mode + sizes from the server and cannot change them locally (the mode button is hidden
  once joined, as in the current uncommitted working tree).
- Values on the wire and in preferences are unchanged.

---

## 4. Everything else: repositories become the source of truth

**Direction.** The alternative is to make `Globals` the truth and delete the repositories. It would be less typing, but
`Globals` is a bag of public mutable fields that can't validate, notify, or update compound values atomically, which
are the properties §3 needs. The repositories already have accessors, tests (`GameSettingsRepositoryTest`,
`PlayerRepositoryTest`, `GameStateRepositoryTest`) and the newer callers. So `Globals` fields move to the repositories.
For any value that is a dead mirror on the repository side today, "moving" means the repository copy becomes the live
one and the `Globals` field is deleted.

**Mechanics, per group:**

1. Delete the `Globals` field. The compiler then lists every site.
2. Rewrite each site to the repository accessor. Kotlin callers in `domain/` keep going through `GameConfig`, whose
   `GlobalsGameConfig` becomes `RepositoryGameConfig`, so the engine is untouched.
3. Delete that value from `syncToRepositories()` and from any manual dual-writes (`TcpClient:867/873`,
   `DedicatedServerActivity:283/307/313/645`, `PlayerSettingsAlertDialog:379`, `TcpServer:321`).
4. Where the value is set as part of a group from one network message (the `TcpClient` game-info block), add a
   repository method that applies the group in one call. Example: `applyServerGameSettings(…)` for limits + GPS +
   start time + allow-choose-teams + only-server-settings, so readers don't observe a half-applied update.

**Groups, in order:**

| Group | Values | Notes |
|---|---|---|
| A. Team mode | §3 | First; fixes the shipped bug properly |
| B. Network-dictated game settings | limits, GPS mode (+ use-GPS), start time, allow-choose-teams, only-server-settings, server recoil setting | Fixes §2.2 and §2.3. Applied by one method from `TcpClient`. |
| C. Player settings | health, reload, reload time, respawn & shield time, damage, override lives, reload-on-empty, shot-mode allowances, firing mode, allow-player-settings | Mostly flows through `GameConfig`, so few non-engine sites |
| D. Game state | game state, server time remaining, server IP | `GameStateRepository` already has listeners and a snapshot. `GameConfig.gameState` setter writes the repository. |
| E. Local identity | player ID, name, admin, paired grenade | Delete `updatePlayerIDFromRepo` / `updatePlayerNameFromRepo` and the `…Direct` setters. One setter each. |
| F. Collections & locks | §5 | Last, because it touches the most `TcpServer` code |

After group F, `Globals` holds only constants and `getTeamColor()`. Those move to the repositories / a `TeamColors`
object and `Globals` is deleted (Q3).

---

## 5. Locking target

- **One owner per piece of data.** `PlayerRepository` owns the player maps and grenade pairings and is the only lock
  for them. `GameRoster` keeps its own lock for roster records and calls repository methods (never raw maps) to keep
  the projection in step. Lock order is **`GameRoster` → `PlayerRepository` → `GameSettingsRepository`**, and no
  repository calls back up the chain.
- **No raw internals escape.** `getGrenadePairingsInternal()`, `getIpTeamMap()` etc. return read-only views or copies.
  The `TcpServer` sites that read-modify-write them under a `Globals` semaphore get repository methods instead. For
  example, `pairGrenade(grenadeId, playerId)` replaces `TcpServer:1199`, and `grenadePairingsSnapshot()` replaces
  `:1035-1038`.
- **Delete the six `Globals` semaphores** once their last user is converted. There are 27 acquire sites today, so this
  is a per-site review, not a mechanical replace.
- **`volatile` alone is fine for single independent values** (most of groups B–E). Values that must change together
  get one immutable holder or one repository method, as in §3.

---

## 6. Plan

Each step builds, passes `./gradlew test`, and leaves the app shippable.

| Step | Work | Tests |
|---|---|---|
| 1 | Add `TeamMode`, `TeamLayout` (+ `TeamLayoutTest`: defaults, `teamOf` boundaries for 2/4 teams and custom sizes, invalid sizes rejected, `fromWire` round-trip and rejection) | new |
| 2 | `GameSettingsRepository` holds `AtomicReference<TeamLayout>` + listeners/StateFlow. `PlayerRepository` team math delegates to snapshots. | Update `GameSettingsRepositoryTest`, `PlayerRepositoryTest`. Add a concurrency test: writer flips modes while readers assert every snapshot is self-consistent. |
| 3 | Migrate all team-mode call sites (§3.4). Delete `Globals` mode/size members and the interim `setGameMode`. Unify the preferences accessor. Validate ADMIN_GAMEMODE and save it. | `GameViewModelTest` (existing server-less 4-team test stays green), `TcpServerLoopbackTest` (admin mode change, invalid value ignored), `HitResolverTest` (friendly fire in 4 teams) |
| 4 | Group B, incl. `applyServerGameSettings` | Client applies a game-info message → repository reflects all values; GPS teammate filter test against the repository |
| 5 | Groups C, D, E; `GlobalsGameConfig` → `RepositoryGameConfig` | `BlasterSessionEngineTest` unchanged and green |
| 6 | Group F (locks, collections) | `GameRosterTest`, `TcpServerLoopbackTest`; review each converted semaphore site |
| 7 | Remove `Globals` (Q3) | Full suite + on-device checklist from `REFACTOR_DESIGN.md` §3.0 |

On-device check after step 3: server-less 2/4/FFA cycling and friendly fire; client joins a 4-team server; admin
changes mode from a client; dedicated server restarts with the saved mode.

---

## 7. Decisions (all resolved 2026-10-13)

- **Q1 — User-facing wording: "Team Mode".** The main screen label, the network menu entry and the dedicated server
  button all say "Team Mode". Resource IDs are renamed to match. The wire protocol and preference keys are unchanged
  (§3.1).
- **Q2 — Scope: everything, in one pass.** Steps 1–7 of §6 land together. The step order is still the order of work,
  and the full suite must pass at the end.
- **Q3 — `Globals` is deleted.** Constants and types move to the class that owns them:
  - setting constants → `GameSettingsRepository`
  - game-state constants → `GameStateRepository`
  - player-ID constants, `GPSData` and `PlayerSettings` → `PlayerRepository` / their own classes
  - team colours and team modes → `domain/team`
- **Q4 — `MapLocationManager` is wired up.** `MapFragment` delegates location tracking and marker drawing to it, and
  its duplicate copies of that logic are removed. `MapLocationManager` reads team mode, GPS mode and player ID from the
  repositories.

---

## 8. Implementation record (2026-10-13)

All of §6 landed in one pass on `V2-refactor` (uncommitted). `./gradlew testDebugUnitTest` passes: **439 tests, 0
failures** (424 before, plus the new tests below). `assembleDebug` builds. `lintDebug` reports no errors. The one new
warning is a `RelativeOverlap` on the bottom-centre team mode container, which needs checking on a device.

### 8.1 Where things went

| Was | Now |
|---|---|
| `Globals` (deleted) and `GlobalsTest` (deleted) | — |
| `Globals.mGameMode`, `mTeamSizes`, `GAME_MODE_*` | `TeamMode` / `TeamLayout` (`domain/team`), held in `GameSettingsRepository` as an `AtomicReference<TeamLayout>` |
| `Globals.TEAM_n_COLOR`, `getTeamColor()` | `TeamColors` (`domain/team`) |
| `Globals.GPSData`, `Globals.PlayerSettings` | `data/model/GpsData`, `data/model/PlayerSettings` |
| Settings fields and constants | `GameSettingsRepository` |
| Game state, server time, server IP; `GAME_STATE_*` | `GameStateRepository` |
| Player ID, name, admin, paired grenade, device address, player maps, `INVALID_PLAYER_ID` etc. | `PlayerRepository` (gained `deviceAddress` and `pairGrenade`; lost `getGrenadePairingsInternal`, `set…Direct` and the `Globals` back-sync) |
| Six `Globals` semaphores | Removed (§8.3) |
| `GlobalsGameConfig` | `RepositoryGameConfig`. `GameConfig.gameMode` + `networkTeam()` became one `teamLayout` |
| "Game Mode" in the UI, resource IDs `game_mode_*`, `game_mode_menu.xml` | "Team Mode", `team_mode_*`, `team_mode_menu.xml` |

Unchanged on purpose: the wire names (`"gamemode"`, `ADMIN_GAMEMODE`) and values (1/2/4), and the preference key
`"GameMode"`.

### 8.2 Deviations from the plan

- **Listeners only, no StateFlow** (§3.3). Only `DedicatedServerViewModel` needs change notification. It turns the
  listener into LiveData, which now drives the server's team mode button instead of five hand-written `setText`
  calls. `GameViewModel` already redisplays after every change it makes or receives.
- **No `applyServerGameSettings`** (§4 step 4). Mode and sizes from the server are applied together through
  `setTeamModeAndSizes`. The other game-info values are independent `volatile` fields applied in the lobby, and no
  reader combines them in a way a half-applied update could break.
- **Grenade pairing keeps a lock in `TcpServer`** (`mGrenadePairingLock`). Its only job is to keep "pair, then
  announce the pairings" in order between connections. It guards no data and never wraps the roster's or
  repository's lock.
- **Server-only lookups no longer consult the phone's own player** (`findAutoSortPlayerID`, `findOpenPlayerIDOnTeam`,
  `isNameUnique`, `purgeDisconnectedPlayers`). Their repository copy of "my ID / my name" was always stale, so in
  practice they never counted the phone's player. With one live copy they would have started reserving the phone
  owner's ID on the dedicated server, which runs in the same process. Dropping the check keeps today's behaviour, and
  the phone-hosted server that needed it is gone (`NETWORK_RECONNECT_DESIGN.md` §3.9).
- **Re-picking the same mode on the dedicated server still resets custom team sizes.** It always did, so this stays
  explicit in `DedicatedServerActivity.applyTeamMode`, even though `setTeamMode` alone keeps sizes for an unchanged
  mode.
- **`MapFragment` stops location updates in `onDestroy`.** `MapLocationManager` is process-wide, so a destroyed
  fragment must not stay registered.

### 8.3 Locking as built

Lock order is **`GameRoster` → `PlayerRepository` → (lock-free) `GameSettingsRepository`**.

- **`TcpServer`:** sites that wrapped a roster call in semaphores (hello, name change, move, kick, rebalance, reset)
  now just call the roster, which updates the repository under its own lock. Sites that touch the repository's maps
  directly (GPS reports and sends, player settings in and out) use `synchronized (PlayerRepository)`. Any roster or
  connection lookups they need now happen before the lock is taken. `getPlayerSettings()` used to call
  `mRoster.isEmpty()` inside the semaphore, and under the repository's lock that would have inverted the order.
- **`TcpClient`, `NetworkSessionGateway`, `PlayerSettingsAlertDialog`, `DedicatedServerActivity`,
  `MapLocationManager`:** `synchronized (PlayerRepository)` around each multi-map read or write. None of them takes
  the roster's lock inside.
- **Team layout:** a compare-and-set loop on an immutable value. Readers take one snapshot per decision:
  - the engine's friendly-fire check (`BlasterSessionEngine.hitLookups(layout)`)
  - every `GameRoster` operation (rebalance, hello, team points, same-side)
  - the server's game info and score messages
  - the scoreboard, the map, and the player settings dialog

### 8.4 Bugs fixed along the way

- Server-less 4 Teams used the 2-team layout for IDs and friendly fire (§2.1). This was the trigger for the work.
- `ADMIN_GAMEMODE` accepted any integer and wasn't saved. Unknown values are now ignored, and valid ones are saved.
- `Globals.getTeamSizes()` pushed a stale mode back into the repository, which could revert a change and drop custom
  sizes (§2.1). The getter no longer exists.
- The player settings dialog worked out "team N – player M" from equal team sizes, so custom ratios showed the wrong
  team. It now reads the layout.
- A non-numeric entry in the player settings dialog threw while holding the player-settings semaphore and never
  released it, which blocked every later reader. `synchronized` always releases.
- The latent GPS-mode split (§2.2) is gone. The live map now runs through `MapLocationManager`, which reads the
  repository.

### 8.5 New and changed tests

- `TeamLayoutTest`: defaults; `teamOf` for 2/4/custom/FFA; start/end IDs; invalid sizes; `withMode`/`withSizes`;
  copy safety; wire values.
- `GameSettingsRepositoryTest`: team tests ported to `TeamLayout`. Added invalid sizes, `setTeamModeAndSizes`,
  listeners, and a concurrency test where readers fail on any snapshot whose sizes or team IDs disagree with its mode.
- `BlasterSessionEngineTest.fourTeams_friendlyFireFollowsTheFourTeamLayout`. Mutation-checked: with the 2-team layout
  it fails because health stays at 20.
- `TcpServerLoopbackTest.adminTeamMode_unknownValue_isIgnored`.
- `MapLocationManagerTest`: teammates-only hides enemies; FFA has no teammates; 4-team layout used.
- `DedicatedServerViewModelTest` now sets only-server-settings in `setUp`. With one shared copy,
  `GameViewModelTest`'s value carried over between tests.

### 8.6 Still to do

- The on-device checklist in §6 (server-less 2/4/FFA and friendly fire; client joins a 4-team server; admin changes
  mode; dedicated server restarts with the saved mode; GPS teammates-only on a map).
- Check the bottom-centre team mode button against the lint `RelativeOverlap` warning on a narrow phone.
