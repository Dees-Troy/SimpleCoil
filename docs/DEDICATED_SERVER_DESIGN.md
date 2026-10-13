# SimpleCoil Dedicated Server: Lifecycle, Screen and Shared Components — Design Document

Status: **APPROVED (2026-10-13).** All decisions in §8 are resolved. No source has been changed.

Revision 2 (2026-10-13): decisions recorded (§8). Stopping the server asks for confirmation on Back (Q1). The device
is kept awake only while the server screen is in the foreground, not after Home (Q3, which replaces the draft's
"locks for as long as the server runs"). §5.1, §5.4, §5.6, §6.2, §9 and §10 are updated to match.
Branch audited: `V2-refactor` @ `b460c02`.
Date: 2026-10-13
Part of: `MODERNIZATION_ROADMAP.md`. Builds on `NETWORK_STACK_DESIGN.md` (dispatcher, event bus, Kotlin
`GameServer`) and follows `APP_CONTAINER_DESIGN.md` for dependencies.

---

## 0. Summary

`DedicatedServerActivity` (976 lines, Java) predates the play-screen refactor. It owns more than a screen should:

- **The server's life.** `onDestroy()` always kicks every player and stops the server, but an activity is also
  destroyed and recreated on every configuration change. A window resize on a Chromebook, a dark-mode toggle, or (now
  that `targetSdk` is 37) a rotation on a large screen **ends the session for every player** (S1).
- **The game clock.** The time limit is enforced by a `CountDownTimer` in the activity, and so is the time remaining
  sent to joining players (S2).
- **The process's priority.** `TcpServer` is not a foreground service, so a minimized or backgrounded server can be
  frozen or killed (S3).

There are also smaller defects: bindings that pile up on every resume, GPS relaying that turns off when the screen
pauses, two discovery beacons, and crashes on empty dialog fields. The screen also duplicates the play screen's game
limit and team ratio dialogs and shares an old `AlertDialog` subclass and `ArrayAdapter` with it.

This document proposes:

1. **Hotfixes first** (§4): small commits that stop the session-ending and crash bugs now, before any restructuring.
2. **The server owns its session** (§5): `TcpServer` becomes a foreground service that hosts `GameServer` (from the
   network design), the discovery beacon and a server-side game clock. It exposes a `StateFlow<ServerSnapshot>`.
   Closing or recreating the screen no longer affects the game. Stopping the server becomes an explicit action.
3. **A thin screen** (§6): Kotlin, ViewBinding, a `DedicatedServerViewModel` built on `StateFlow`, and DialogFragments.
4. **Shared components** (§7) for both screens: host-agnostic dialogs, one `PlayerSettingsDialog` with a pure form
   model, a `RecyclerView` scoreboard built from a pure row builder, and shared game-limit and team-size rules.

---

## 1. Requirements

| # | Requirement |
|---|---|
| R1 | A running server, and any game in it, survives every configuration change, window resize, screen-off, app switch and minimize. |
| R2 | The server stops only when the operator asks it to (or the process dies). |
| R3 | The time limit is enforced by the server, whether or not the screen exists. |
| R4 | Reopening the screen shows the current state: phase, clock, players and settings. |
| R5 | The operator's settings persist through `UserPreferencesRepository` under the same keys, so saved values carry over. |
| R6 | Dialogs survive recreation. Invalid input never crashes the app. |
| R7 | Both screens share one implementation of the game-limit, team-ratio and player-settings dialogs and of the scoreboard. |
| R8 | The wire protocol and everything clients see are unchanged (`NETWORK_STACK_DESIGN.md` §1). |

### 1.1 Behavior that must not change

- The buttons and their visibility per phase (`renderPhase`, :781–799). Reset sits in Start's place and is
  INVISIBLE, not GONE, because other views are laid out against Start.
- The implicit reset before a settings change in POST_GAME, with its toast (:757–760). Explicit Reset still confirms
  with the count of disconnected players (:763–778).
- The clock display: `MM:00` before a timed game. During a timed game, `MM:SS` counting down over the time limit
  **plus** the starting timer (:956), and the same remaining seconds sent in game info. During an untimed game, the
  chronometer starts after the starting timer (:808–818).
- Start Game with one or no players shows the "not enough players" toast (:244–248).
- The allow-join switch keeps the beacon on (:282–288). Only-server-settings and allow-choose-teams re-send game
  info.
- The team header row opens the team ratio dialog, and a player row opens that player's settings (:331–348).

---

## 2. Current state

| Component | Lines | Notes |
|---|---|---|
| `DedicatedServerActivity.java` | 976 | Binds `UDPListenerService` and `TcpServer` in `onResume`. Broadcast receiver for server events. Three `AlertDialog.Builder` dialogs. Two `CountDownTimer`s. `ListView` scoreboard. Raw `SharedPreferences` writes. |
| `ui/server/DedicatedServerViewModel.java` | 203 | `AndroidViewModel` with 12 `LiveData`s, of which the screen observes 4 (server IP, player count, team mode, phase). Starts its **own** `ServerDiscoveryManager` beacon. |
| `PlayerSettingsAlertDialog.java` | 497 | An `AlertDialog` subclass used by both screens: server mode (`setServer`) edits any player, local mode (`setLocal`) edits the local player. Holds a `TcpServer` reference and sends broadcasts. |
| `PlayerDisplayDataListAdapter.java` | 472 | `ArrayAdapter` holding the scoreboard's sorting and grouping (team headers, sort by score, lives, names; c9377b6). Used by the dedicated server and by `ScoreboardDialog`. `PlayerDisplayDataListAdapterTest` (227 lines). |
| `ui/game/GameDialogFragments.kt` | 278 | The play screen's DialogFragments (`GameLimitDialog`, `TeamRatioDialog`, `ScoreboardDialog`, ...), bound to `GameViewModel` through `GameDialogFragment.vm`. |

---

## 3. Defects

Bugs are fixed in their own commits (§9). Items marked **hotfix** go in first (§4).

| # | Defect | Effect | Where |
|---|---|---|---|
| S1 | **Any configuration change ends the session.** The activity declares no `configChanges`, and `onDestroy()` unconditionally sends `SERVERCANCEL` to every client, stops the beacon and stops the server. A recreation (resize, dark mode, locale, density, and rotation on large screens, where API 36+ ignores `screenOrientation`) runs `onDestroy`. The new instance's `onResume` then calls `startTcpServer()` while the old accept loop may still hold port 17510 (up to 1 s), so the new `ServerSocket` fails to bind and the error is only printed. | Every player is kicked, and the server may come back dead. **hotfix** | `DedicatedServerActivity.java:368–381`; `TcpServer.java:695–751` |
| S2 | **The game clock lives in the activity.** The time limit is a `CountDownTimer` whose `onFinish` calls `mTcpServer.endGame()` (:953–974). It is also what writes `GameStateRepository.serverGameTimeRemaining`, which the server sends to joining players. Lose the activity and you lose the time limit. | Timed games never end. Joiners get a stale remaining time. | `:801–821, :953–974` |
| S3 | **`TcpServer` is not a foreground service.** It is started (never stopped) and bound by the activity. With the activity in the background, the process is cached: Android 14+ freezes cached processes, and any version may kill them. The keep-screen-on flag only helps while the screen is visible. | A minimized server (Chromebook) or a backgrounded one (phone) stops answering, and clients drop after 3.5 s. **hotfix** (foreground only) | `AndroidManifest.xml` (`TcpServer` has no type); `:138–157` |
| S4 | **Services are bound again on every resume.** `onResume` calls `setupUDPServiceConnection()` and `setupTcpServerServiceConnection()` every time, creating a new `ServiceConnection` (and calling `createServer()` and `startTcpServer()`) per resume. `onDestroy` unbinds only the last two. This is the same leak the play screen had as B4. | Leaked connections. A beacon restart per resume. | `:353–358, :116–157` |
| S5 | **Two discovery beacons.** `DedicatedServerViewModel` starts its own `ServerDiscoveryManager` beacon (:178 → VM), and `UDPListenerService.createServer()` starts another. Both bind 17500 with `SO_REUSEADDR`, so every discovery query is answered twice. The VM's beacon stops in `onCleared`; the service's doesn't stop until `onDestroy`. | Duplicate traffic. Unclear ownership. | `DedicatedServerViewModel.java:59–66, :107`; `UDPListenerService.java:57–66` |
| S6 | **Pausing turns GPS relaying off.** `onPause` calls `setUseGPS(false)`, and nothing turns it back on: `setGPSMode` only runs from `onCreate` and the menu. After the operator switches apps once, the server stops relaying GPS and game info stops carrying `usegps`. | Players' maps go blank until the operator re-picks a GPS mode. **hotfix** | `:361–365` |
| S7 | **Null `mTcpServer` crashes.** The only-server-settings switch calls `mTcpServer.sendAllGameInfo` without a null check (:294). `onFinish` of the countdown calls `mTcpServer.endGame()` (:970). | A crash if either fires before the service is bound or after it is gone. **hotfix** | listed |
| S8 | **Empty fields crash the player settings dialog.** OK runs `Integer.parseInt` on every field without a guard (`PlayerSettingsAlertDialog.java:351–378, :399–425`). The same applies to the local mode used from the play screen. | Crash on either screen. **hotfix** | listed |
| S9 | **A limit of 0 turns the limit on.** Entering 0 for a limit that is currently off sets its bit (the `limit <= 0` branch only clears a bit that was already set), so a 0-minute time limit or 0 lives can be enabled. The code is duplicated, bug included, in `GameViewModel.onGameLimitEntered` (:499–529). | A timed game ends right after the starting timer. | `:523–543` |
| S10 | **The scoreboard jumps to the top on every update.** `getPlayerDisplayData()` calls `setAdapter` each time (:946–950), and it runs on every join, leave and elimination. | The operator can't keep a scrolled position during a game. | listed |
| S11 | **A team switch combined with apply-to-all/team silently skips the copy.** The dialog calls `switchPlayerTeam` (a new thread that moves the player's settings to the new id) and then immediately `sendPlayerSettings(newId, applyAll, applyTeam, ...)`, which reads the settings under the new id before the move has happened. | The settings are not applied to the others. The player's own settings arrive in a later snapshot. | `PlayerSettingsAlertDialog.java:439–453`; `TcpServer.java:323–337` |
| S12 | **Firing mode is read back from the button's text** (:432–437). | Breaks under translation. | listed |
| S13 | **Settings persistence bypasses the repository.** `PREF_GPS_MODE` ("GPSMode") and `PREF_ALLOW_CHOOSE_TEAMS` are raw keys, and the limits and start time are written through `FullscreenActivity.PREF_*` (:72–73, :261–278, :583–587, :626). | Two persistence paths for the same settings. | listed |
| S14 | **Dead and duplicated state.** 8 of the VM's 12 `LiveData`s have no observer, and its settings setters are only called from tests. The activity keeps its own copies in fields. `PlayerRepository.setPlayerID(0)` and a game-state reset in `onCreate` (:164–165) quietly tidy up the client role's leftovers (see `APP_CONTAINER_DESIGN.md` Q3). | Confusing ownership. | listed |
| S15 | **Hardcoded strings.** The toasts at :426, :645, :651, :657, :733, :738 and the dialog title at :690. `String.format` without a locale (:563, :838, :959) can render non-ASCII digits. | Not translatable. Lint `DefaultLocale`. | listed |

---

## 4. Phase 0 — hotfixes (Java, each one small commit)

These are worth shipping before anything else in the roadmap.

| Step | Change | Test |
|---|---|---|
| H1 (S1) | Add `android:configChanges="orientation\|screenSize\|screenLayout\|smallestScreenSize\|uiMode\|density\|keyboard\|keyboardHidden\|navigation\|locale\|layoutDirection"` to `DedicatedServerActivity`, so the activity handles those changes itself instead of being recreated. Guard `onDestroy`'s shutdown with `if (isFinishing())`. | Device: toggle dark mode, resize the Chromebook window, rotate a tablet during a game. No client is kicked. |
| H2 (S3) | `TcpServer.startAsForeground()` mirrors `TcpClient`: a notification channel, `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`, and `foregroundServiceType="connectedDevice"` on `TcpServer` in the manifest. The permission prerequisite is met by `CHANGE_WIFI_MULTICAST_STATE`. Called when the server starts and stopped in `stopTcpServer()`. | Device: minimize the server on a Chromebook, or switch apps on a phone, for 5 minutes during a game. The server is not frozen (Chromebook: clients stay connected). |
| H3 (S6) | Delete `setUseGPS(false)` from `onPause`. (It probably dates from the removed phone-hosted mode.) | Device: switch away and back. GPS keeps relaying. |
| H4 (S7) | Null-check `mTcpServer` at :294 and :970. | — |
| H5 (S8) | Use `toIntOrDefault` parsing in `PlayerSettingsAlertDialog`, so an empty or invalid field falls back to the default the range check already uses. | Unit test of the extracted parse helper. |

S4, S5 and S9–S15 are fixed during the restructuring (§9), because the code they live in is replaced.

---

## 5. The server owns its session

### 5.1 Shape

```
TcpServer (foreground Service, connectedDevice)  ─ hosts ─►  GameServer (NETWORK_STACK_DESIGN §7.1 step 6)
   │                                                       DiscoveryBeacon (the only beacon; S5)
   │                                                       ServerGameClock (§5.3)
   │                                                       WifiLock while the screen is in the foreground (§5.6)
   ├─ snapshot: StateFlow<ServerSnapshot>   (updated on the dispatcher after every change; conflated)
   └─ serverEvents (NETWORK_STACK_DESIGN §6): one-shot messages (errors, time expired, cancelled)

ServerController (app-scoped, in the container)
   start(): startForegroundService + bind;  stop(): stop the server, stopSelf;  commands → TcpServer
   isRunning: StateFlow<Boolean>
```

`ServerController` hides binding from the UI. The activity, its ViewModel and the notification actions all talk to
it, and the binding to the service is owned in one place (fixes S4).

### 5.2 `ServerSnapshot`

```kotlin
data class ServerSnapshot(
    val phase: SessionPhase,
    val serverIp: String?,
    val players: List<ServerPlayerRow>,            // id, name, points, eliminated, connected, lives override
    val connectedCount: Int,
    val clock: ClockState,
    val settings: ServerSettings,                  // team layout, limits, start time, gps mode, flags
)
sealed interface ClockState {
    data object Idle : ClockState
    data class Starting(val endsAtElapsed: Long) : ClockState
    data class Counting(val endsAtElapsed: Long) : ClockState      // timed game
    data class Elapsed(val startedAtElapsed: Long) : ClockState    // untimed game
}
```

The snapshot replaces `getPlayerDisplayData()`'s 32 calls to `getScore()` under the repository lock, and the screen's
own copies of the state. The clock is published as **timestamps**, not ticks. The screen renders `MM:SS` once a
second from `SystemClock.elapsedRealtime()`, so a recreated screen shows the right time without the server having
to tick the UI.

### 5.3 `ServerGameClock`

A plain Kotlin class with an injected clock and scope, running on the server dispatcher. It is built on the existing
`countDown()` helper (`domain/session/CountdownTimer.kt`), which already reproduces `CountDownTimer`'s tick
arithmetic.

- `start(timeLimitMinutes, startTimeSeconds)`:
  - **Timed:** count down `limit·60 s + start s` in 1 s ticks. Each tick writes
    `GameStateRepository.serverGameTimeRemaining = millisLeft / 1000`, as :964 does today. At the end it calls
    `GameServer.endGame()` and publishes `ServerEvent.Error(time expired)` for the toast (:969).
  - **Untimed:** after `start s`, publish `Elapsed(startedAt)`.
- `stop()` on end game and on reset sets the remaining time to 0, as :836 does.
- Unit-tested under virtual time: tick values, the end-of-time call, and stop.

### 5.4 Lifecycle

- **Start:** opening the dedicated server screen calls `ServerController.start()` (today the server starts as soon as
  the screen opens). If the server is already running, the screen just reconnects to its snapshot (R4).
- **Stop:** only through `ServerController.stop()`, which is reached from:
  - Back on the screen, with a confirmation ("Stop the server? N players will be disconnected."); see Q1.
  - The notification's **Stop server** action.
  Stopping sends `SERVERCANCEL` to every client (as `onDestroy` does today), stops the beacon, closes the sockets and
  calls `stopSelf()`.
- **Screen destroyed:** unbinds only. The server is unaffected (R1).
- **Notification:** "SimpleCoil server — N players — <phase>". Tapping it opens the screen. On API 33+ the screen
  requests `POST_NOTIFICATIONS` once when the server starts. The Chromebook path never goes through the BLE permission
  flow that requests it today. Without the permission the service still runs, only the notification is hidden.
- **Process death:** the server is gone, and clients retry as described in `NETWORK_RECONNECT_DESIGN.md`. Restoring a
  server after process death is out of scope.

### 5.6 Keeping the device awake (Q3)

The device stays awake whenever the dedicated server screen is in the foreground. When the operator goes to the home
screen (or another app), the app lets the device sleep.

| Server screen | Screen | Wi-Fi | Server |
|---|---|---|---|
| Foreground (started; on a Chromebook this includes an unfocused but visible window) | Kept on (`FLAG_KEEP_SCREEN_ON`, as today) | `WifiLock` held: `WIFI_MODE_FULL_LOW_LATENCY` on API 29+, `WIFI_MODE_FULL_HIGH_PERF` below | Runs normally |
| Background (Home, another app) | Normal timeout | Lock released | Keeps running in its foreground service on a best-effort basis (Q2) |

- The activity reports its foreground state from `onStart` / `onStop` to `ServerController.setScreenVisible(Boolean)`,
  and the service acquires or releases the `WifiLock` accordingly. The service owns the lock, so it is also released
  when the server stops.
- No partial `WakeLock`. In the background a phone may sleep. Then the 1 s GPS tick, pings and clock ticks can slip,
  and clients may drop and reconnect (`NETWORK_RECONNECT_DESIGN.md`) when it wakes. The game clock is computed from
  `elapsedRealtime` (§5.3), which keeps counting during sleep, so a timed game still ends at the right moment once
  the device wakes. A Chromebook doesn't sleep while the lid is open, so it is unaffected.
- The `WifiLock` requires the `WAKE_LOCK` permission (a normal permission, granted at install).

### 5.5 Settings

The operator's settings go through `UserPreferencesRepository` (S13), with new accessors on the **same keys**: `GPSMode`,
`AllowChooseTeams`, and `StartTime`, `TimeLimit`, `LivesLimit`, `ScoreLimit`, which already exist there. The service
loads them into `GameSettingsRepository` when the server starts, in place of the activity's `onCreate` (:263–279).
Saved values carry over.

---

## 6. The screen

### 6.1 `DedicatedServerViewModel` (Kotlin)

```kotlin
data class ServerUiState(
    val phase: SessionPhase, val serverIpText: String, val playerCountText: Int,
    val clock: ClockState, val limitsSummary: LimitsSummary, val teamMode: TeamMode, val gpsMode: Int,
    val startTimeSeconds: Long, val allowJoin: Boolean, val onlyServerSettings: Boolean,
    val allowChooseTeams: Boolean, val scoreboard: List<ScoreboardRow>, val versionName: String,
)
sealed interface ServerUiEffect { data class Toast(@StringRes val text: Int, val args: List<Any> = emptyList()) : ServerUiEffect /* ... */ }

class DedicatedServerViewModel(controller: ServerController, prefs: UserPreferencesRepository, ...) : ViewModel() {
    val state: StateFlow<ServerUiState>           // from controller.snapshot + settings
    val effects: Flow<ServerUiEffect>             // toasts; from serverEvents + validation
    fun onStartPressed(); fun onEndPressed(); fun onResetConfirmed(); fun onStopConfirmed()
    fun onTeamModeSelected(mode); fun onSortTeams(); fun onGpsModeSelected(mode)
    fun onGameLimitEntered(text, kind); fun onStartTimeEntered(text); fun onTeamSizesEntered(texts)
    fun onAllowJoinChanged(on); fun onOnlyServerSettingsChanged(on); fun onAllowChooseTeamsChanged(on)
    fun onPlayerSettingsSaved(result: PlayerSettingsResult); fun onKick(id)
}
```

The settings-change handlers do the implicit reset in POST_GAME first, with the toast. The rules are unit-tested with
a fake `ServerController`, the way `GameViewModelTest` tests the play screen. The existing
`DedicatedServerViewModelTest` (Java) is ported, and its assertions on the unused `LiveData`s go away with them.

### 6.2 `DedicatedServerActivity` (Kotlin)

- ViewBinding over `activity_dedicated_server.xml`. The layout is unchanged except `ListView` → `RecyclerView` (same
  id, `player_list`).
- `repeatOnLifecycle(STARTED)` collects `state` → `DedicatedServerRenderer.render(state)`, which absorbs the phase
  rules from `renderPhase`, and `effects` → toasts.
- The clock view: a 1 s ticker while STARTED that formats `ClockState`. `Chronometer` is used for `Elapsed`, with
  `base` mapped from `startedAtElapsed`.
- Popup menus (team mode, GPS mode) stay `PopupMenu`s and call the VM.
- Keep-screen-on while in the foreground, plus `setScreenVisible` from `onStart` / `onStop` (§5.6).
- The H1 `configChanges` stays, so a resize doesn't flash. Correctness no longer depends on it.
- All toasts and titles come from string resources (S15). Clock formatting goes through one helper shared with the play screen
  (`BlasterSessionEngine.kt:285, :450` and `GameScreenRenderer.kt:143` format the same way today, without a locale);
  see `PLATFORM_COMPAT_DESIGN.md` §8 for which locale it uses.

---

## 7. Shared components (both screens)

### 7.1 Host-agnostic dialogs

The play screen's dialogs reach `GameViewModel` through `GameDialogFragment.vm`, so the server screen can't reuse
them. The dialogs that both screens need move to the **Fragment Result API**: the dialog validates its input, then
calls `setFragmentResult(REQUEST_KEY, bundle)`, and each host routes the result to its own ViewModel.

| Dialog | Today | After |
|---|---|---|
| Game limit | `GameLimitDialog` (play screen), `requestGameLimit` (server, :464–556) | One `GameLimitDialog`, result `(text, kind)`. Both VMs apply it through `GameLimitRules` (§7.3). |
| Team sizes | `TeamRatioDialog` (play screen), `showTeamRatioDialog` (server, :649–751) | One `TeamRatioDialog`, result `texts`. Both VMs validate through `TeamSizes.validate` (§7.3). Keeps the stay-open-on-error behavior (:704–750) through a VM validation call before dismissing. |
| Starting timer | Server only (:592–640) | `StartingTimerDialog` |
| Reset confirm | `ResetConfirmDialog` (play screen), `confirmReset` (server) | One dialog with a message argument (the server's includes the disconnected count) |
| Stop server | — | `StopServerConfirmDialog` (Q1) |
| Player settings | `PlayerSettingsAlertDialog` (both, §7.2) | `PlayerSettingsDialog` |

### 7.2 `PlayerSettingsDialog` and `PlayerSettingsForm`

- `PlayerSettingsForm` is pure Kotlin. It turns the dialog's raw texts and toggles into a validated
  `WirePlayerSettings` (the type from `NETWORK_STACK_DESIGN.md` §7.3), with today's range rules and defaults (health
  1–1000 → 20, shots 1–255 → 30, reload 0–10000 ms → 1500, ...), and returns a field error when no shot mode is
  allowed. It never throws (S8). It gets a table-driven unit test of every field's bounds.
- The firing mode is held as a value, not read from the button text (S12).
- `PlayerSettingsDialog` is a DialogFragment with two modes chosen by its arguments, `Local` and `Server(playerId)`.
  It shows and hides the same rows as today (admin, kick, recoil and switch team are server-only; firing mode and
  apply-all/team are server-only).
- The result goes to the host: `GameViewModel.onLocalSettingsSaved` (which sends `playersettings` as `TcpClient`
  does now) or `DedicatedServerViewModel.onPlayerSettingsSaved`.
- **Server-side save as one dispatcher operation** (S11): `GameServer.applyPlayerSettings(id, settings, applyAll,
  applyTeam, targetTeam?)` does the optional move, the store, the copy to all or the team, and the sends, in that
  order and on the dispatcher. The race goes away.

### 7.3 Shared rules

- `GameLimitRules.apply(current: Limits, kind, value): Limits`. Fixes S9: a value ≤ 0 turns the limit off, a value
  > 0 turns it on, and > 100 is rejected with `LIMIT_TOO_HIGH` as today. Used by both VMs.
- `TeamSizes.validate(texts, teamCount): Result<IntArray>`, with today's messages (each ≥ 1, the total must be 32).

### 7.4 Scoreboard

- `ScoreboardRows.build(players, layout, isClient): List<ScoreboardRow>` is a pure function holding the grouping and
  sorting now in `PlayerDisplayDataListAdapter` (team headers with totals; sorted by score, then lives remaining or
  eliminations, then name). `PlayerDisplayDataListAdapterTest` is ported to it and must pass with the same orderings.
- `ScoreboardAdapter : ListAdapter<ScoreboardRow, VH>` with `DiffUtil` and two row types (header, player). It uses
  the existing `player_display_team_header.xml`, `player_display_data.xml` and `player_display_data_client.xml`
  unchanged. Updates no longer reset the scroll position (S10).
- `ScoreboardDialog` (play screen) and the server screen both use it. `player_data_dialog.xml`'s `ListView` becomes a
  `RecyclerView`.
- Click handling: header row → team ratio dialog; player row → player settings (server only), as today.

---

## 8. Decisions (all resolved 2026-10-13)

| # | Question | Options | Recommendation |
|---|---|---|---|
| Q1 | How does the operator stop the server? | Back asks for confirmation, plus a notification action / a Stop button on the screen / Back stops silently (today) | **Decided: Back asks for confirmation before stopping the server.** The notification's Stop action is kept (accepted default). |
| Q2 | Should the server keep running when the operator leaves the screen with Home (not Back)? | Yes / no | **Accepted default: yes** (R1), in its foreground service, on a best-effort basis while the device may sleep (Q3). |
| Q3 | Keeping the device awake | Locks while the server runs / while the screen is in the foreground / none | **Decided: always keep the device awake unless the operator is on the home screen.** Screen on and a `WifiLock` while the server screen is in the foreground; everything released in the background. No partial `WakeLock` (§5.6). |
| Q4 | Use `ServerSnapshot` for the UI, or keep server events plus pulls? | Snapshot / events + pulls | **Accepted default: snapshot.** One conflated state removes the 32-call refresh and the risk of a missed event leaving the screen stale. Events stay for toasts. |
| Q5 | When `GameLimitRules` fixes S9 on the play screen too: does an admin on the play screen send limits anywhere? | — | No: the play screen only sets limits for server-less games. The fix is local to both screens and doesn't touch the wire. |

---

## 9. Plan

The gate on every step: `./gradlew test` and `assembleDebug` pass. **[device]** marks a step that ends with the
dedicated-server check (§10).

| Step | Work | Depends on |
|---|---|---|
| 0 | Hotfixes H1–H5 (§4), one commit each. **[device]** | — |
| 1 | `ScoreboardRows` + `ScoreboardAdapter`. Port `PlayerDisplayDataListAdapterTest`. Switch both screens to `RecyclerView` (fixes S10). Delete `PlayerDisplayDataListAdapter`. **[device]** | — |
| 2 | `GameLimitRules` and `TeamSizes` used by both screens, then the S9 fix in its own commit (it updates the test pinning 0-enables). | — |
| 3 | Fragment Result API for the game limit and team ratio dialogs. The server screen uses them. `StartingTimerDialog`. Delete the three builder dialogs. **[device]** | 2 |
| 4 | `PlayerSettingsForm` + `PlayerSettingsDialog` for both screens. Delete `PlayerSettingsAlertDialog`. | 3 |
| 5 | `UserPreferencesRepository` accessors for the server's settings, with the same keys (S13). | — |
| 6 | `ServerGameClock` (unit-tested). `TcpServer` hosts it; the activity's timers are deleted (S2). **[device]** | Network Phase 1 (dispatcher) |
| 7 | `ServerSnapshot` + `ServerController` (single binding, S4; single beacon, S5). `GameServer.applyPlayerSettings` (S11). **[device]** | Network Phase 2 (bus), Phase 3 step 6 (`GameServer`) |
| 8 | `DedicatedServerViewModel` (Kotlin, StateFlow) + `DedicatedServerActivity` (Kotlin, ViewBinding, renderer). Stop confirmation and notification actions (Q1). Foreground-only awake policy (§5.6, Q3). Strings (S15). **[device]** | 7 |

Steps 1–5 do not depend on the network work and can start right after Phase 0.

---

## 10. Verification

**Dedicated-server check (devices, ~15 min; Chromebook and phone if available):**

1. Open the server, three clients join, start a timed game. Then, during the game:
   - toggle dark mode;
   - resize or maximize the Chromebook window;
   - rotate a tablet;
   - leave the screen in the foreground for 5 minutes without touching it: the screen stays on;
   - press Home and wait 5 minutes (Chromebook: no client is kicked; phone: the device may sleep, and clients that
     drop reconnect once it wakes);
   - reopen from the notification.
   Apart from the phone-in-background case, no client is kicked. The clock is correct after each one, and a timed
   game ends on time even with the screen closed (on a sleeping phone: as soon as it wakes).
2. Back → confirmation → Cancel: the server keeps running. Back → Stop: every client gets `SERVERCANCEL`.
3. The notification's Stop action does the same as 2.
4. GPS relaying continues after switching away and back (H3).
5. Player settings: an empty field → defaults, no crash. Switch team + apply to team: the teammates get the settings
   (S11).
6. Scrolled scoreboard: the position is kept through joins and eliminations.
7. Game limit 0 on a limit that is off: it stays off (S9). Team ratio validation messages match today's.
8. The settings persist across server restarts (same keys).
9. Everything in `NETWORK_STACK_DESIGN.md` §9.1 item 2 (admin commands from a client) still updates the server screen.

---

## 11. Out of scope

Restoring a server after process death; hosting the server and playing on the same device at the same time (`APP_CONTAINER_DESIGN.md`
Q3); a new server UI design or Material3 styling (`PLATFORM_COMPAT_DESIGN.md` §7); map features.
